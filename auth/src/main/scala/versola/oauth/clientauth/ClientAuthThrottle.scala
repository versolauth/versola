package versola.oauth.clientauth

import versola.util.CoreConfig
import versola.util.http.{
  Checkpoint,
  ClientIpSource,
  GuardMode,
  IpAddress,
  RateLimitScope,
  Observability,
  RateDecision,
  RateLimitDimension,
  RateLimitKeys,
  RateLimitPolicy,
  RateLimitWindow,
  RateLimiter,
}
import zio.*
import zio.http.*
import zio.logging.logContext

/** Limits on auth's own channel, the one that is not mutual TLS (#745): the endpoints a client authenticates to with a secret it
  * can be guessed at, and the entry to the authorization flow.
  *
  * **Failed client authentication** (`/token`, `/par`, `/introspect`, `/revoke`): an address that has failed `failedPerMinute` times is
  * refused with `429` before its next request is read, however good the credential in it. Only failures are counted, so a
  * client that authenticates is never limited and a shared address is not refused for its honest users. Guessing a client's
  * secret at the token endpoint is the classic unthrottled path; the secret's own entropy is what protects it, and this makes
  * the guesses slow as well. On by default (`enforce`).
  *
  * **Entry** (`/authorize`): every call counts, by address. Off the critical path of a signed-in user and the most expensive
  * thing a stranger can ask for. `shadow` by default: it counts what it would refuse (`auth_rate_limit_decisions_total`) until
  * an operator has read the numbers and chosen a limit, because a deployment's honest callers can share an address (a load
  * balancer, a campaign's drivers).
  *
  * The address is the peer of the connection (`socket`), or the header the deployment's proxy sets (`forwarded`, see
  * [[ClientIpSource]]; trusted only if auth is reachable solely through that proxy). Memory is bounded (a flood of addresses
  * shares an overflow bucket) and state is per pod, so N pods allow N times a limit.
  *
  * Configuration: the `limits` block of auth's config file ([[Settings]]; every key optional). Absent, the defaults above apply.
  */
final class ClientAuthThrottle private (
    val settings: ClientAuthThrottle.Settings,
    val failed: RateLimiter,
    val entry: RateLimiter,
)

object ClientAuthThrottle:
  final case class Settings(
      failedMode: GuardMode = GuardMode.Enforce,
      failedPerMinute: Int = 20,
      failedBurst: Int = 10,
      entryMode: GuardMode = GuardMode.Shadow,
      entryPerMinute: Int = 600,
      entryBurst: Int = 100,
      clientIp: ClientIpSource = ClientIpSource.Socket,
      ipHeader: String = "X-Real-IP",
  ):
    /** The first thing wrong, if anything: a limit that cannot be applied stops the service from starting rather than turning a
      * protection off. */
    def validate: Either[String, Settings] =
      def range(name: String, value: Int) = Either.cond(value >= 1 && value <= 1_000_000, (), s"limits.$name must be between 1 and 1000000")
      for
        _ <- range("failed-per-minute", failedPerMinute)
        _ <- range("failed-burst", failedBurst)
        _ <- range("entry-per-minute", entryPerMinute)
        _ <- range("entry-burst", entryBurst)
        _ <- Either.cond(failedBurst <= failedPerMinute, (), "limits.failed-burst must not exceed failed-per-minute")
        _ <- Either.cond(entryBurst <= entryPerMinute, (), "limits.entry-burst must not exceed entry-per-minute")
        _ <- Either.cond(ipHeader.nonEmpty, (), "limits.ip-header must not be empty")
      yield this

  object Settings:
    val default: Settings = Settings()

  def make(settings: Settings): UIO[ClientAuthThrottle] =
    def policy(name: String, mode: GuardMode, perMinute: Int, burst: Int) =
      RateLimitPolicy(
        name = name,
        by = List(RateLimitDimension.Ip),
        limits = List(RateLimitWindow(limit = perMinute, windowSeconds = 60)),
        burst = burst,
        mode = mode,
        scope = RateLimitScope.EdgeWide,
        cost = 1,
      )
    for
      failed <- RateLimiter.make(metricPrefix = "auth")
      entry <- RateLimiter.make(metricPrefix = "auth")
      _ <- failed.update(List(policy("failed-client-auth-per-address", settings.failedMode, settings.failedPerMinute, settings.failedBurst)))
      _ <- entry.update(List(policy("authorize-per-address", settings.entryMode, settings.entryPerMinute, settings.entryBurst)))
    yield new ClientAuthThrottle(settings, failed, entry)

  /** From the `limits` block of the config file (the defaults when absent), with its sweep and metrics running for the life of the
    * service. A block that does not validate fails the start. */
  val live: ZLayer[Scope & CoreConfig, Throwable, ClientAuthThrottle] =
    ZLayer.fromZIO:
      for
        scope <- ZIO.service[Scope]
        config <- ZIO.service[CoreConfig]
        settings <- ZIO.fromEither(config.limits.getOrElse(Settings.default).validate).mapError(IllegalArgumentException(_))
        throttle <- make(settings)
        _ <- ZIO.logInfo(s"auth limits: $settings")
        _ <- throttle.failed.run.forkIn(scope)
        _ <- throttle.entry.run.forkIn(scope)
      yield throttle

  /** The endpoints a client proves a secret to. */
  private val FailedAuthenticationPatterns = Set("/token", "/par", "/introspect", "/revoke")

  /** What a failed client authentication is called by the endpoints that set it (`ErrorCode.InvalidClient`). */
  private val InvalidClient = "invalid_client"

  private enum Kind:
    case None, FailedAuth, Entry

  private def kindOf(method: Method, pattern: String): Kind =
    if method == Method.POST && FailedAuthenticationPatterns.contains(pattern) then Kind.FailedAuth
    else if pattern == "/authorize" then Kind.Entry
    else Kind.None

  /** Wraps the routes of the main listener. Not applied to the mutual-TLS listener: a certificate is not a guessable secret. */
  def apply[Env <: ClientAuthThrottle, Err](routes: Routes[Env, Err]): Routes[Env, Err] =
    Routes.fromIterable(
      routes.routes.map: route =>
        kindOf(route.routePattern.method, route.routePattern.pathCodec.render) match
          case Kind.None => route
          case kind => route.transform[Env](protect(kind)),
    )

  private def address(request: Request, settings: Settings): Option[String] =
    val socket = request.remoteAddress.flatMap(IpAddress.normalize)
    settings.clientIp match
      case ClientIpSource.Socket => socket
      case ClientIpSource.Forwarded =>
        request.headers.get(settings.ipHeader).map(_.trim).flatMap(value => IpAddress.normalize(value.split(',').last.trim)).orElse(socket)

  private def protect[Env <: ClientAuthThrottle](kind: Kind)(
      handler: Handler[Env, Response, Request, Response],
  ): Handler[Env, Response, Request, Response] =
    Handler.scoped[Env]:
      Handler.fromFunctionZIO[Request]: request =>
        ZIO.serviceWithZIO[ClientAuthThrottle]: throttle =>
          val limiter = if kind == Kind.Entry then throttle.entry else throttle.failed
          if !limiter.active(Checkpoint.Address) then handler(request)
          else
            address(request, throttle.settings) match
              case None => handler(request)
              case Some(ip) =>
                val keys = RateLimitKeys(ip = ip)
                Clock.nanoTime.flatMap: now =>
                  if kind == Kind.Entry then refuseIfLimited(limiter, limiter.check(Checkpoint.Address, keys, now)) *> handler(request)
                  else
                    refuseIfLimited(limiter, limiter.gate(Checkpoint.Address, keys, now)) *>
                      handler(request).tap: response =>
                        // A failed client authentication is what is charged: `401` (RFC 6749 §5.2, which every one of these endpoints
                        // answers it with) or the `invalid_client` the handler recorded (a `400` for a client that did not use Basic).
                        logContext.get
                          .map(context => response.status == Status.Unauthorized || context.get(Observability.error).exists(_.code == InvalidClient))
                          .flatMap(failed => ZIO.when(failed)(Clock.nanoTime.map(at => limiter.charge(Checkpoint.Address, keys, at))))

  private def refuseIfLimited(limiter: RateLimiter, decision: RateDecision): IO[Response, Unit] =
    decision match
      case RateDecision.Allowed => ZIO.unit
      case limited @ RateDecision.Limited(policy, retryAfterSeconds) =>
        Observability.setError("rate_limited", Some(policy)) *>
          ZIO.fail(
            // no body: a body here would be a contract; `Retry-After` and `RateLimit` say what a caller can act on
            Response.status(Status.TooManyRequests)
              .addHeader(Header.CacheControl.NoStore)
              .addHeader(Header.Custom("Retry-After", retryAfterSeconds.toString))
              .addHeaders(Headers(limiter.responseHeaders(limited).map((name, value) => Header.Custom(name, value)))),
          )

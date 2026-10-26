package versola.edge

import versola.util.http.{Checkpoint, Observability, RateDecision, RateLimitKeys, RateLimiter}
import zio.*
import zio.config.magnolia.DeriveConfig
import zio.http.*
import zio.json.{EncoderOps, JsonEncoder}
import zio.metrics.{Metric, MetricLabel}

import java.net.{Inet4Address, Inet6Address, InetAddress}
import java.security.cert.X509Certificate

/** Where the edge reads a caller's address from (`client-ip` in the edge's config file).
  *
  * `socket`: the peer of the connection. Right when callers reach the edge directly. Behind a reverse proxy or load balancer it
  * is the proxy's address, the same for everyone, so an address limit would count the whole world as one caller.
  *
  * `forwarded`: the header named by `client-ip-header` (`X-Real-IP` by default), which nginx and ingress-nginx set from the
  * client chain they have already resolved. **Never trusted unless the edge is reachable only through the proxy that sets it**
  * (compose network, chart network policy): any other caller could write the header and choose its own bucket.
  */
enum ClientIpSource(val name: String):
  case Socket extends ClientIpSource("socket")
  case Forwarded extends ClientIpSource("forwarded")

object ClientIpSource:
  given DeriveConfig[ClientIpSource] = DeriveConfig[String].mapOrFail: value =>
    values.find(_.name == value)
      .toRight(Config.Error.InvalidData(message = s"client-ip must be one of ${values.map(_.name).mkString(", ")}, not '$value'"))

/** The caller's address as a rate-limit key, and how it was found. */
object ClientAddress:
  /** Where the answer came from: the header (`forwarded`), the connection (`socket`), or the connection because the header the
    * setting names was missing or not an address (`forwarded_missing`, a misconfiguration the metric makes visible).
    */
  enum Origin(val label: String):
    case Forwarded extends Origin("forwarded")
    case Socket extends Origin("socket")
    case ForwardedMissing extends Origin("forwarded_missing")

  final case class Resolved(address: Option[String], origin: Origin)

  def resolve(request: Request, source: ClientIpSource, header: String): Resolved =
    val socket = request.remoteAddress.flatMap(normalize)
    source match
      case ClientIpSource.Socket => Resolved(socket, Origin.Socket)
      case ClientIpSource.Forwarded =>
        request.headers.get(header).map(_.trim).flatMap(normalize) match
          case Some(address) => Resolved(Some(address), Origin.Forwarded)
          case None => Resolved(socket, Origin.ForwardedMissing)

  /** IPv4 as written, IPv6 by its /64 (one subscriber holds a whole /64, so a bucket per address is no bucket at all). An
    * IPv4-mapped IPv6 address is the IPv4 address. Text that is not an IP literal is `None`: it never reaches a resolver.
    */
  def normalize(literal: String): Option[String] =
    if literal.isEmpty || literal.length > 45 || !literal.forall(c => c.isDigit || c == '.' || c == ':' || (c.toLower >= 'a' && c.toLower <= 'f')) then None
    else if literal.contains(':') || FourParts.matches(literal) then
      scala.util.Try(InetAddress.getByName(literal)).toOption.flatMap(normalize)
    // `1.2.3` and `16909060` are addresses to the JDK; they are not what a proxy writes, so they are not accepted
    else None

  private val FourParts = "\\d{1,3}(\\.\\d{1,3}){3}".r

  def normalize(address: InetAddress): Option[String] =
    address match
      case v4: Inet4Address => Some(v4.getHostAddress)
      case v6: Inet6Address =>
        val bytes = v6.getAddress
        Some("v6/" + bytes.take(8).map(b => f"${b & 0xff}%02x").mkString)
      case _ => None

/** R1: the rate limit checkpoint that runs before the token is read, keyed on what the connection shows.
  *
  * On the **plain listener** the key is the caller's address, and what is counted is **failed and unauthenticated attempts
  * only**: `/login`, `/complete` and the rest of the entry points count every call (nobody is signed in), a proxied call counts
  * only when it was refused as unauthenticated (a missing, invalid or expired token; not a step-up, a nonce retry or a
  * re-login redirect). A request from an address whose bucket is empty is refused with `429` before anything else. Successful
  * requests are never limited by address, so a mobile carrier's shared address does not refuse its honest users.
  *
  * On the **mutual-TLS listener** the key is the verified client certificate's thumbprint, and every call counts: a certificate is
  * an identity, and no address is needed or reliably known behind an L4 proxy.
  *
  * With no policy at [[Checkpoint.Address]] this is one volatile read and nothing else.
  */
object AddressLimit:
  enum Listener:
    case Plain, MutualTls

  private enum Kind:
    case Skip, Entry, Authenticated

  private val clientAddresses = Metric.counter("edge_client_address_total")

  private case class RateLimitedBody(error: String, policy: String) derives JsonEncoder

  private def kindOf(pattern: String): Kind =
    if pattern.startsWith("/service/") || pattern == "/logout/backchannel" then Kind.Skip
    else if pattern.startsWith("/login/") || pattern == "/complete" || pattern.startsWith("/native/start/") ||
      pattern.startsWith("/native/complete/") || pattern.startsWith("/logout/")
    then Kind.Entry
    else Kind.Authenticated

  /** A refusal of the caller's credential, as opposed to a normal answer on the way to a success: a `401` that is not a nonce
    * challenge, a step-up challenge or a redirect to sign in again.
    */
  private[edge] def isFailedAttempt(response: Response): Boolean =
    response.status == Status.Unauthorized &&
      response.header(Header.Location).isEmpty &&
      response.rawHeader("DPoP-Nonce").isEmpty &&
      !response.rawHeader("WWW-Authenticate").exists(_.contains("insufficient_user_authentication"))

  def apply[Env <: RateLimiter & EdgeConfig, Err](routes: Routes[Env, Err], listener: Listener): Routes[Env, Err] =
    Routes.fromIterable(
      routes.routes.map: route =>
        val kind = if listener == Listener.MutualTls then Kind.Authenticated else kindOf(route.routePattern.pathCodec.render)
        if kind == Kind.Skip then route
        else route.transform[Env](protect(kind, listener)),
    )

  private def protect[Env <: RateLimiter & EdgeConfig](kind: Kind, listener: Listener)(
      handler: Handler[Env, Response, Request, Response],
  ): Handler[Env, Response, Request, Response] =
    Handler.scoped[Env]:
      Handler.fromFunctionZIO[Request]: request =>
        for
          limiter <- ZIO.service[RateLimiter]
          response <-
            if !limiter.active(Checkpoint.Address) then handler(request)
            else
              for
                config <- ZIO.service[EdgeConfig]
                keys <- keysOf(request, listener, config, limiter)
                now <- Clock.nanoTime
                response <- keys match
                  case None => handler(request)
                  case Some(keys) => run(kind, listener, limiter, keys, now, handler, request)
              yield response
        yield response

  private def keysOf(request: Request, listener: Listener, config: EdgeConfig, limiter: RateLimiter): UIO[Option[RateLimitKeys]] =
    listener match
      case Listener.MutualTls =>
        ZIO.succeed(request.remoteCertificate.collect { case certificate: X509Certificate =>
          RateLimitKeys(certificate = TokenBinding.thumbprint(certificate))
        })
      case Listener.Plain =>
        val resolved = ClientAddress.resolve(request, config.clientIp, config.clientIpHeader)
        clientAddresses.tagged(MetricLabel("source", resolved.origin.label)).increment
          .as(resolved.address.map(address => RateLimitKeys(ip = address)))

  private def run[Env](
      kind: Kind,
      listener: Listener,
      limiter: RateLimiter,
      keys: RateLimitKeys,
      now: Long,
      handler: Handler[Env, Response, Request, Response],
      request: Request,
  ): ZIO[Env & Scope, Response, Response] =
    // an entry point, or any call of a certificate: every call counts, so check and charge in one step
    if kind == Kind.Entry || listener == Listener.MutualTls then
      refuseIfLimited(limiter.check(Checkpoint.Address, keys, now)) *> handler(request)
    else
      // a proxied call on the plain listener: refused if the address has used up its failed attempts, charged only if this one fails
      refuseIfLimited(limiter.gate(Checkpoint.Address, keys, now)) *>
        handler(request).tap: response =>
          ZIO.when(isFailedAttempt(response))(Clock.nanoTime.map(at => limiter.charge(Checkpoint.Address, keys, at)))

  private def refuseIfLimited(decision: RateDecision): IO[Response, Unit] =
    decision match
      case RateDecision.Allowed => ZIO.unit
      case RateDecision.Limited(policy, retryAfterSeconds) =>
        Observability.setError("rate_limited", Some(policy)) *>
          ZIO.fail(
            Response.json(RateLimitedBody(error = "rate_limited", policy = policy).toJson)
              .status(Status.TooManyRequests)
              .addHeader(Header.Custom("Retry-After", retryAfterSeconds.toString)),
          )

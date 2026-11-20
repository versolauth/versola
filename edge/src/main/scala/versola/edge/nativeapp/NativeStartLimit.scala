package versola.edge.nativeapp

import versola.edge.{ClientAddress, EdgeConfig, EdgeMode, EdgeRateLimiter, RateLimitPolicySource}
import versola.util.http.{
  Checkpoint,
  ClientIpSource,
  GuardMode,
  NativeStartRateLimit,
  Observability,
  RateDecision,
  RateLimitKeys,
  RateLimitPolicy,
  RateLimiter,
}
import zio.*
import zio.http.Request

/** The limit on `/native/start` per source address, the first step of a start.
  *
  * The endpoint takes no client credential: whoever can reach it can make edge verify a proof, record it and push a request to auth,
  * which stores it. Without any limit a flood costs auth and the database a write per call, and the device proofs a flood needs
  * cost the sender nothing (any fresh key will do).
  *
  * What is applied is the `nativeStart` of the edge's rate limits in central ([[NativeStartRateLimit]]): an operator sets it per edge in
  * the console, a partner once for its group. While it is not set the edge applies [[NativeStartRateLimit.default]] (60 calls a minute,
  * bursts of 20): only counting (`shadow`) on an edge of the operator, refusing (`enforce`) on a partner's edge. Like every limit it
  * is divided by the number of pods.
  *
  * The default applies only when the address is one this edge can tell callers apart by: `client-ip = forwarded`. With `socket`
  * behind a proxy every caller would have the proxy's address and the limit would count the whole world as one caller, so there
  * nothing applies until somebody sets a limit, who then knows what the address is. A call whose forwarded header is missing or
  * not an address is never counted.
  *
  * It is counted in a limiter of its own (`edge_native_start_rate_limit_decisions_total{policy,result}`, `policy` being
  * `native-start` for a limit that was set and `native-start-default` for the default), apart from the policies of the edge.
  */
final class NativeStartLimit private (own: RateLimiter, config: EdgeConfig):

  /** Fails with the wait in whole seconds when this call is over a limit that refuses. */
  def enforce(request: Request): IO[NativeError, Unit] =
    if !own.active(Checkpoint.Address) then ZIO.unit
    else
      val resolved = ClientAddress.resolve(request, config.clientIp, config.clientIpHeader)
      resolved.address.filter(_ => resolved.origin != ClientAddress.Origin.ForwardedMissing) match
        case None => ZIO.unit
        case Some(address) =>
          Clock.nanoTime.flatMap: now =>
            own.check(Checkpoint.Address, RateLimitKeys(ip = address), now) match
              case RateDecision.Allowed => ZIO.unit
              case RateDecision.Limited(policy, retryAfterSeconds) =>
                Observability.setError("rate_limited", Some(policy)) *> ZIO.fail(NativeError.RateLimited(retryAfterSeconds))

object NativeStartLimit:

  /** What an edge applies: the limit that was set, as written; else the default where the address tells callers apart. */
  def effective(configured: Option[NativeStartRateLimit], mode: EdgeMode, clientIp: ClientIpSource): Option[RateLimitPolicy] =
    configured match
      case Some(limit) => Some(limit.toPolicy(NativeStartRateLimit.PolicyName))
      case None if clientIp == ClientIpSource.Forwarded =>
        val guard = if mode == EdgeMode.Partner then GuardMode.Enforce else GuardMode.Shadow
        Some(NativeStartRateLimit.default(guard).toPolicy(NativeStartRateLimit.DefaultPolicyName))
      case None => None

  /** `configured` and `replicas` are read now and then once a second for the life of the scope; a change is applied and logged once.
    * A limit that fails [[NativeStartRateLimit.validate]] (central validates before storing, so this is a central that disagrees
    * with this edge) is not applied and the previous one stays.
    */
  def make(configured: UIO[Option[NativeStartRateLimit]], replicas: UIO[Int], config: EdgeConfig): URIO[Scope, NativeStartLimit] =
    for
      own <- RateLimiter.make(metricPrefix = "edge_native_start")
      applied <- Ref.make(Option.empty[(Option[NativeStartRateLimit], Int)])
      refresh = (configured <*> replicas).flatMap: current =>
        ZIO.unlessZIO(applied.get.map(_.contains(current))):
          val (limit, pods) = current
          // remembered either way, so a limit that stays invalid is reported once and not every second
          applied.set(Some(current)) *> (limit.fold[Either[String, Unit]](Right(()))(_.validate.map(_ => ())) match
            case Left(why) => ZIO.logWarning(s"native start rate limit not applied: $why")
            case Right(_) =>
              val policy = effective(limit, config.mode, config.clientIp)
              own.update(policy.toList, pods) *> ZIO.logInfo(
                s"native start rate limit applied: ${policy.fold("none")(p =>
                    s"${p.name}=${p.mode.toString.toLowerCase} ${p.limits.head.limit}/${p.limits.head.windowSeconds}s burst ${p.burst}",
                  )}, replicas $pods",
              ))
      _ <- refresh
      scope <- ZIO.service[Scope]
      _ <- refresh.repeat(Schedule.fixed(1.second)).forever.forkIn(scope)
      _ <- own.run.forkIn(scope)
    yield NativeStartLimit(own, config)

  val live: URLayer[RateLimitPolicySource & EdgeConfig & Scope, NativeStartLimit] =
    ZLayer.fromZIO:
      for
        source <- ZIO.service[RateLimitPolicySource]
        config <- ZIO.service[EdgeConfig]
        pods <- EdgeRateLimiter.replicas(config, source)
        limit <- make(source.nativeStart, pods, config)
      yield limit

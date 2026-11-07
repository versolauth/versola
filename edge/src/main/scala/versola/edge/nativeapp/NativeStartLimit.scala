package versola.edge.nativeapp

import versola.edge.{ClientAddress, EdgeConfig}
import versola.util.http.{
  Checkpoint,
  ClientIpSource,
  GuardMode,
  Observability,
  RateDecision,
  RateLimitDimension,
  RateLimitKeys,
  RateLimitPolicy,
  RateLimitScope,
  RateLimitWindow,
  RateLimiter,
}
import zio.*
import zio.http.Request

/** What an edge does about `/native/start` before its operator has written a rate limit for it.
  *
  * The endpoint takes no client credential: whoever can reach it can make edge verify a proof, record it and push a request to auth,
  * which stores it. Without any limit a flood costs auth and the database a write per call, and the device proofs a flood needs
  * cost the sender nothing (any fresh key will do).
  *
  * So until an operator installs a policy at the address checkpoint (which counts this route with the other entry points, see
  * [[versola.edge.AddressLimit]]), each address gets [[NativeStartLimit.Default]]: 60 calls a minute with bursts of 20, per pod. It
  * is generous for a person (one call per sign-in) and small for a flood.
  *
  * It applies only when the address is one this edge can tell callers apart by: `client-ip = forwarded` and the header present. With
  * `socket` behind a proxy every caller would have the proxy's address and the limit would count the whole world as one caller, so
  * there it does nothing, as before; an operator who wants a limit there sets a policy.
  */
final class NativeStartLimit private (operator: RateLimiter, own: RateLimiter, config: EdgeConfig):

  /** Fails with the wait in whole seconds when this call is over the default limit. */
  def enforce(request: Request): IO[NativeError, Unit] =
    if config.clientIp != ClientIpSource.Forwarded || operator.active(Checkpoint.Address) then ZIO.unit
    else
      val resolved = ClientAddress.resolve(request, config.clientIp, config.clientIpHeader)
      resolved.address.filter(_ => resolved.origin == ClientAddress.Origin.Forwarded) match
        case None => ZIO.unit
        case Some(address) =>
          Clock.nanoTime.flatMap: now =>
            own.check(Checkpoint.Address, RateLimitKeys(ip = address), now) match
              case RateDecision.Allowed => ZIO.unit
              case RateDecision.Limited(policy, retryAfterSeconds) =>
                Observability.setError("rate_limited", Some(policy)) *> ZIO.fail(NativeError.RateLimited(retryAfterSeconds))

object NativeStartLimit:

  val Default: RateLimitPolicy = RateLimitPolicy(
    name = "native-start-default",
    by = List(RateLimitDimension.Ip),
    scope = RateLimitScope.EdgeWide,
    limits = List(RateLimitWindow(limit = 60, windowSeconds = 60)),
    burst = 20,
    cost = 1,
    mode = GuardMode.Enforce,
  )

  /** `operator` is the edge's limiter, only asked whether it has an address policy; the default is counted in a limiter of its own, so
    * it shows in its own metrics (`edge_native_start_rate_limit_decisions_total`) and is never mistaken for a policy of the operator. */
  def make(operator: RateLimiter, config: EdgeConfig): URIO[Scope, NativeStartLimit] =
    for
      own <- RateLimiter.make(metricPrefix = "edge_native_start")
      _ <- own.update(List(Default))
      scope <- ZIO.service[Scope]
      _ <- own.run.forkIn(scope)
    yield NativeStartLimit(operator, own, config)

  val live: URLayer[RateLimiter & EdgeConfig, NativeStartLimit] =
    ZLayer.scoped:
      for
        operator <- ZIO.service[RateLimiter]
        config <- ZIO.service[EdgeConfig]
        limit <- make(operator, config)
      yield limit

package versola.edge

import versola.util.http.{ClientIpSource, RateLimitPolicy, RateLimiter}
import zio.*

/** The rate limit policies this edge should apply now. Nothing in request handling reads it: [[RateLimitPolicyApplier]] polls
  * it and hands what it yields to the running [[RateLimiter]], so a source only has to answer "what is configured", and a
  * source that syncs from central later (like [[OverloadPolicyService]] does for the overload policy) is a drop-in.
  */
trait RateLimitPolicySource:
  def current: UIO[List[RateLimitPolicy]]

  /** The number of pods the traffic is shared by; a limit is divided by it. */
  def replicas: UIO[Int] = ZIO.succeed(1)

object RateLimitPolicySource:
  /** The policies central holds for this edge, as [[RateLimitPolicyService]] syncs them. */
  val fromCentral: URLayer[RateLimitPolicyService, RateLimitPolicySource] =
    ZLayer.fromFunction((service: RateLimitPolicyService) =>
      new RateLimitPolicySource:
        override val current: UIO[List[RateLimitPolicy]] = service.current
        override val replicas: UIO[Int] = service.replicas,
    )

  /** No policy at all (tests, tools): the limiter stays disabled and a request pays one volatile read. */
  val none: ULayer[RateLimitPolicySource] =
    ZLayer.succeed(new RateLimitPolicySource:
      override val current: UIO[List[RateLimitPolicy]] = ZIO.succeed(Nil))

/** Keeps a [[RateLimiter]] at the policies a source currently holds. A set of policies that fails
  * [[RateLimitPolicy.validateAll]] is not applied and the previous one stays. `enforce` policies refuse (`429`); `shadow` ones
  * only count.
  */
object RateLimitPolicyApplier:
  /** Applies `policies` if they are valid. `Left` carries what was wrong with them. */
  def apply(policies: List[RateLimitPolicy], limiter: RateLimiter, replicas: Int = 1): UIO[Either[String, Unit]] =
    RateLimitPolicy.validateAll(policies) match
      case Left(why) => ZIO.succeed(Left(why))
      case Right(valid) => limiter.update(valid, replicas).as(Right(()))

  /** Polls `source` once a second and applies what it yields when it changed, for the life of the service. A change is logged
    * once, with names and modes (never keys); nothing is logged while the policies stay the same.
    */
  def run(source: UIO[List[RateLimitPolicy]], limiter: RateLimiter, replicas: UIO[Int] = ZIO.succeed(1)): UIO[Nothing] =
    Ref.make[Option[(List[RateLimitPolicy], Int)]](None).flatMap: applied =>
      (source <*> replicas)
        .flatMap: current =>
          val (policies, pods) = current
          applied.get.flatMap: previous =>
            if previous.contains(current) then ZIO.unit
            else
              RateLimitPolicyApplier(policies, limiter, pods).flatMap:
                case Right(_) =>
                  applied.set(Some(current)) *> ZIO.logInfo(s"rate limit policies applied: ${describe(policies)}, replicas $pods")
                case Left(why) =>
                  // remembered, so a set that stays invalid is reported once and not every second
                  applied.set(Some(current)) *> ZIO.logWarning(s"rate limit policies not applied: $why")
        .repeat(Schedule.fixed(1.second))
        .forever

  private def describe(policies: List[RateLimitPolicy]): String =
    if policies.isEmpty then "none" else policies.map(p => s"${p.name}=${p.mode.toString.toLowerCase}").mkString(", ")

/** The edge's [[RateLimiter]], fed by the [[RateLimitPolicySource]], with its sweep and metrics publishing running for the life
  * of the service.
  */
object EdgeRateLimiter:
  val live: ZLayer[RateLimitPolicySource & Scope & EdgeConfig, Nothing, RateLimiter] =
    ZLayer.fromZIO:
      for
        source <- ZIO.service[RateLimitPolicySource]
        config <- ZIO.service[EdgeConfig]
        _ <- ZIO.logWarning(
          s"client-ip = forwarded: the address limit trusts the ${config.clientIpHeader} header, so this edge must be reachable only through the proxy that sets it",
        ).when(config.clientIp == ClientIpSource.Forwarded)
        scope <- ZIO.service[Scope]
        resolved <- config.replicasFrom match
          case None => ZIO.succeed(ZIO.succeed(Option.empty[Int]))
          case Some(spec) =>
            ZIO.fromEither(ReplicaCounter.parse(spec)).mapError(IllegalArgumentException(_)).orDie.flatMap(name => ReplicaCounter.dns(name, ReplicaCounter.resolver, 10.seconds).provideEnvironment(ZEnvironment(scope)))
        limiter <- RateLimiter.make()
        _ <- RateLimitPolicyApplier.run(source.current, limiter, ReplicaCounter.effective(resolved, source.replicas)).forkIn(scope)
        _ <- limiter.run.forkIn(scope)
      yield limiter

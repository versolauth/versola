package versola.edge

import versola.util.http.{GuardMode, RateLimitPolicy, RateLimiter}
import zio.*

/** The rate limit policies this edge should apply now. Nothing in request handling reads it: [[RateLimitPolicyApplier]] polls
  * it and hands what it yields to the running [[RateLimiter]], so a source only has to answer "what is configured", and a
  * source that syncs from central later (like [[OverloadPolicyService]] does for the overload policy) is a drop-in.
  */
trait RateLimitPolicySource:
  def current: UIO[List[RateLimitPolicy]]

object RateLimitPolicySource:
  /** The policies central holds for this edge, as [[RateLimitPolicyService]] syncs them. */
  val fromCentral: URLayer[RateLimitPolicyService, RateLimitPolicySource] =
    ZLayer.fromFunction((service: RateLimitPolicyService) =>
      new RateLimitPolicySource:
        override val current: UIO[List[RateLimitPolicy]] = service.current,
    )

  /** No policy at all (tests, tools): the limiter stays disabled and a request pays one volatile read. */
  val none: ULayer[RateLimitPolicySource] =
    ZLayer.succeed(new RateLimitPolicySource:
      override val current: UIO[List[RateLimitPolicy]] = ZIO.succeed(Nil))

/** Keeps a [[RateLimiter]] at the policies a source currently holds.
  *
  * **Shadow only.** The edge counts `would_limit` and refuses nothing: a policy set to `enforce` is applied as `shadow` (and
  * said so once), until enforcement is wired in. A set of policies that fails [[RateLimitPolicy.validateAll]] is not applied
  * and the previous one stays.
  */
object RateLimitPolicyApplier:
  /** Applies `policies` if they are valid. `Left` carries what was wrong with them. */
  def apply(policies: List[RateLimitPolicy], limiter: RateLimiter): UIO[Either[String, Unit]] =
    RateLimitPolicy.validateAll(policies) match
      case Left(why) => ZIO.succeed(Left(why))
      case Right(valid) => limiter.update(valid.map(shadowOnly)).as(Right(()))

  private[edge] def shadowOnly(policy: RateLimitPolicy): RateLimitPolicy =
    if policy.mode == GuardMode.Enforce then policy.copy(mode = GuardMode.Shadow) else policy

  /** Polls `source` once a second and applies what it yields when it changed, for the life of the service. A change is logged
    * once, with names and modes (never keys); nothing is logged while the policies stay the same.
    */
  def run(source: UIO[List[RateLimitPolicy]], limiter: RateLimiter): UIO[Nothing] =
    Ref.make[Option[List[RateLimitPolicy]]](None).flatMap: applied =>
      source
        .flatMap: policies =>
          applied.get.flatMap: previous =>
            if previous.contains(policies) then ZIO.unit
            else
              RateLimitPolicyApplier(policies, limiter).flatMap:
                case Right(_) =>
                  applied.set(Some(policies)) *>
                    ZIO.logInfo(s"rate limit policies applied: ${describe(policies)}") *>
                    ZIO.when(policies.exists(_.mode == GuardMode.Enforce))(
                      ZIO.logWarning("rate limit mode enforce is applied as shadow: this edge does not refuse requests yet"),
                    ).unit
                case Left(why) =>
                  // remembered, so a set that stays invalid is reported once and not every second
                  applied.set(Some(policies)) *> ZIO.logWarning(s"rate limit policies not applied: $why")
        .repeat(Schedule.fixed(1.second))
        .forever

  private def describe(policies: List[RateLimitPolicy]): String =
    if policies.isEmpty then "none" else policies.map(p => s"${p.name}=${p.mode.toString.toLowerCase}").mkString(", ")

/** The edge's [[RateLimiter]], fed by the [[RateLimitPolicySource]], with its sweep and metrics publishing running for the life
  * of the service.
  */
object EdgeRateLimiter:
  val live: ZLayer[RateLimitPolicySource & Scope, Nothing, RateLimiter] =
    ZLayer.fromZIO:
      for
        source <- ZIO.service[RateLimitPolicySource]
        scope <- ZIO.service[Scope]
        limiter <- RateLimiter.make()
        _ <- RateLimitPolicyApplier.run(source.current, limiter).forkIn(scope)
        _ <- limiter.run.forkIn(scope)
      yield limiter

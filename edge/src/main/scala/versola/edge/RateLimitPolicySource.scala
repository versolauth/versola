package versola.edge

import versola.util.http.{ClientIpSource, DeclaredLimit, RateLimitPolicy, RateLimiter}
import zio.*

/** The rate limit policies this edge should apply now. Nothing in request handling reads it: [[RateLimitPolicyApplier]] polls
  * it and hands what it yields to the running [[RateLimiter]], so a source only has to answer "what is configured", and a
  * source that syncs from central later (like [[OverloadPolicyService]] does for the overload policy) is a drop-in.
  */
trait RateLimitPolicySource:
  def current: UIO[List[RateLimitPolicy]]

  /** The number of pods the traffic is shared by; a limit is divided by it. */
  def replicas: UIO[Int] = ZIO.succeed(1)

  /** The limits declared on resource endpoints and on OAuth clients, applied together with [[current]]. */
  def declared: UIO[List[DeclaredLimit]] = ZIO.succeed(Nil)

object RateLimitPolicySource:
  /** The policies central holds for this edge, as [[RateLimitPolicyService]] syncs them, and the limits declared on endpoints and
    * clients, as [[DeclaredRateLimitService]] derives them.
    */
  val fromCentral: URLayer[RateLimitPolicyService & DeclaredRateLimitService, RateLimitPolicySource] =
    ZLayer.fromFunction((service: RateLimitPolicyService, declaredService: DeclaredRateLimitService) =>
      new RateLimitPolicySource:
        override val current: UIO[List[RateLimitPolicy]] = service.current
        override val replicas: UIO[Int] = service.replicas
        override val declared: UIO[List[DeclaredLimit]] = declaredService.current,
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
  def apply(
      policies: List[RateLimitPolicy],
      limiter: RateLimiter,
      replicas: Int = 1,
      declared: List[DeclaredLimit] = Nil,
  ): UIO[Either[String, Unit]] =
    RateLimitPolicy.validateAll(policies) match
      case Left(why) => ZIO.succeed(Left(why))
      case Right(valid) => limiter.update(valid, replicas, declared).as(Right(()))

  /** The declarations that pass the rules of a policy. One that does not is left out and named, so one bad endpoint (central
    * validates before storing, so this is a central that disagrees with this edge) cannot hold back the others.
    */
  def validDeclared(declared: List[DeclaredLimit]): (List[DeclaredLimit], List[String]) =
    val checked = declared.map(d => d.limit.validate(d.owner).fold(why => Left(s"${d.owner.policyName}: $why"), _ => Right(d)))
    (checked.collect { case Right(d) => d }, checked.collect { case Left(why) => why }.distinct)

  /** Polls `source` once a second and applies what it yields when it changed, for the life of the service. A change is logged
    * once, with names and modes (never keys); nothing is logged while the policies stay the same.
    */
  def run(
      source: UIO[List[RateLimitPolicy]],
      limiter: RateLimiter,
      replicas: UIO[Int] = ZIO.succeed(1),
      declared: UIO[List[DeclaredLimit]] = ZIO.succeed(Nil),
  ): UIO[Nothing] =
    Ref.make[Option[(List[RateLimitPolicy], Int, List[DeclaredLimit])]](None).flatMap: applied =>
      (source <*> replicas <*> declared)
        .flatMap: current =>
          val (policies, pods, declaredLimits) = current
          applied.get.flatMap: previous =>
            if previous.contains(current) then ZIO.unit
            else
              val (usable, refused) = validDeclared(declaredLimits)
              ZIO.logWarning(s"rate limits declared on endpoints or clients not applied: ${refused.mkString("; ")}").when(refused.nonEmpty) *>
              RateLimitPolicyApplier(policies, limiter, pods, usable).flatMap:
                case Right(_) =>
                  applied.set(Some(current)) *> ZIO.logInfo(
                    s"rate limit policies applied: ${describe(policies)}, replicas $pods, declared ${describeDeclared(usable)}",
                  )
                case Left(why) =>
                  // remembered, so a set that stays invalid is reported once and not every second
                  applied.set(Some(current)) *> ZIO.logWarning(s"rate limit policies not applied: $why")
        .repeat(Schedule.fixed(1.second))
        .forever

  // counts by owner, never the endpoint or the client
  private def describeDeclared(declared: List[DeclaredLimit]): String =
    if declared.isEmpty then "none"
    else declared.groupMapReduce(_.owner.policyName)(_ => 1)(_ + _).toList.sorted.map((name, n) => s"$name=$n").mkString(", ")

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
        limiter <- RateLimiter.make()
        _ <- RateLimitPolicyApplier.run(source.current, limiter, source.replicas, source.declared).forkIn(scope)
        _ <- limiter.run.forkIn(scope)
      yield limiter

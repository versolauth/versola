package versola.edge

import versola.util.http.{Checkpoint, GuardMode, RateDecision, RateLimitDimension, RateLimitKeys, RateLimitPolicy, RateLimitWindow, RateLimiter}
import zio.*
import zio.test.*
import versola.util.http.RateLimitScope

object RateLimitPolicyApplierSpec extends ZIOSpecDefault:

  private def policy(name: String, mode: GuardMode) =
    RateLimitPolicy(
      name = name,
      by = List(RateLimitDimension.Client),
      limits = List(RateLimitWindow(limit = 1, windowSeconds = 3600)),
      mode = mode,
      scope = RateLimitScope.EdgeWide,
      burst = 1,
      cost = 1,
    )

  private val keys = RateLimitKeys(client = "web")

  def spec = suite("RateLimitPolicyApplier")(
    test("a policy set to enforce is applied as enforce: the limiter refuses, and names the policy and the wait") {
      for
        limiter <- RateLimiter.make()
        result <- RateLimitPolicyApplier(List(policy("enforced", GuardMode.Enforce)), limiter)
        decisions = (1 to 3).map(_ => limiter.check(Checkpoint.Identity, keys, 0L))
      yield assertTrue(
        result == Right(()),
        decisions.head == RateDecision.Allowed,
        decisions.tail.forall(_ == RateDecision.Limited("enforced", 3600)),
        limiter.stats("enforced").map(s => (s.allowed, s.limited, s.wouldLimit)) == Some((1L, 2L, 0L)),
      )
    },
    test("a set that does not validate is not applied and the previous one stays") {
      for
        limiter <- RateLimiter.make()
        _ <- RateLimitPolicyApplier(List(policy("good", GuardMode.Shadow)), limiter)
        bad = policy("good", GuardMode.Shadow).copy(limits = Nil)
        result <- RateLimitPolicyApplier(List(bad), limiter)
        duplicated <- RateLimitPolicyApplier(List(policy("twice", GuardMode.Shadow), policy("twice", GuardMode.Shadow)), limiter)
      yield assertTrue(
        result.left.exists(_.contains("limits")),
        duplicated.left.exists(_.contains("same name")),
        limiter.names == List("good"),
      )
    },
    test("no policy leaves the limiter inactive") {
      for
        limiter <- RateLimiter.make()
        _ <- RateLimitPolicyApplier(Nil, limiter)
      yield assertTrue(!limiter.active(Checkpoint.Identity), !limiter.active(Checkpoint.Route))
    },
    test("run follows the source: a policy appears, changes and goes") {
      for
        source <- Ref.make(List.empty[RateLimitPolicy])
        limiter <- RateLimiter.make()
        fiber <- RateLimitPolicyApplier.run(source.get, limiter).fork
        _ <- TestClock.adjust(1.second)
        none = limiter.names
        _ <- source.set(List(policy("one", GuardMode.Shadow)))
        _ <- TestClock.adjust(1.second)
        one = limiter.names
        _ <- source.set(List(policy("two", GuardMode.Off)))
        _ <- TestClock.adjust(1.second)
        off = limiter.active(Checkpoint.Identity)
        _ <- source.set(Nil)
        _ <- TestClock.adjust(1.second)
        _ <- fiber.interrupt
      yield assertTrue(none.isEmpty, one == List("one"), !off, limiter.names.isEmpty)
    },
    test("the default source has no policy") {
      for current <- ZIO.serviceWithZIO[RateLimitPolicySource](_.current).provide(RateLimitPolicySource.none)
      yield assertTrue(current.isEmpty)
    },
    test("replicas divide a limit: each pod allows its share, with at least the cost in burst") {
      val shared = RateLimitPolicy(
        name = "shared",
        by = List(RateLimitDimension.Client),
        limits = List(RateLimitWindow(limit = 40, windowSeconds = 60)),
        burst = 4,
        mode = GuardMode.Enforce,
        scope = RateLimitScope.EdgeWide,
        cost = 1,
      )
      for
        alone <- RateLimiter.make()
        podOfFour <- RateLimiter.make()
        _ <- RateLimitPolicyApplier(List(shared), alone, replicas = 1)
        _ <- RateLimitPolicyApplier(List(shared), podOfFour, replicas = 4)
        // a burst of 4 on one pod, 1 on a pod of four; then one request per 1.5 s versus per 6 s
        burstAlone = (1 to 5).map(_ => alone.check(Checkpoint.Identity, keys, 0L))
        burstPod = (1 to 2).map(_ => podOfFour.check(Checkpoint.Identity, keys, 0L))
        paceAlone = alone.check(Checkpoint.Identity, keys, 2_000_000_000L)
        pacePod = podOfFour.check(Checkpoint.Identity, keys, 2_000_000_000L)
      yield assertTrue(
        burstAlone.count(_ == RateDecision.Allowed) == 4,
        burstPod.count(_ == RateDecision.Allowed) == 1,
        paceAlone == RateDecision.Allowed,
        pacePod == RateDecision.Limited("shared", 4),
      )
    },
    test("run follows a change of replicas") {
      for
        limiter <- RateLimiter.make()
        pods <- Ref.make(1)
        fiber <- RateLimitPolicyApplier.run(ZIO.succeed(List(policy("one", GuardMode.Enforce))), limiter, pods.get).fork
        _ <- TestClock.adjust(1.second)
        _ <- pods.set(2)
        _ <- TestClock.adjust(1.second)
        _ <- fiber.interrupt
      yield assertTrue(limiter.names == List("one"))
    },
    test("the central source serves what the policy service holds, and follows it") {
      for
        held <- Ref.make(List(policy("from-central", GuardMode.Shadow)))
        service = new RateLimitPolicyService:
          override val current: UIO[List[RateLimitPolicy]] = held.get
          override val replicas: UIO[Int] = ZIO.succeed(3)
          override val refreshNow: Task[Unit] = ZIO.unit
        source = ZLayer.succeed[RateLimitPolicyService](service) >>> RateLimitPolicySource.fromCentral
        first <- ZIO.serviceWithZIO[RateLimitPolicySource](_.current).provide(source)
        _ <- held.set(Nil)
        second <- ZIO.serviceWithZIO[RateLimitPolicySource](_.current).provide(source)
      yield assertTrue(first.map(_.name) == List("from-central"), second.isEmpty)
    },
  ) @@ TestAspect.silentLogging

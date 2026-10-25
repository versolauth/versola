package versola.edge

import versola.util.http.{Checkpoint, GuardMode, RateDecision, RateLimitDimension, RateLimitKeys, RateLimitPolicy, RateLimitWindow, RateLimiter}
import zio.*
import zio.test.*

object RateLimitPolicyApplierSpec extends ZIOSpecDefault:

  private def policy(name: String, mode: GuardMode) =
    RateLimitPolicy(
      name = name,
      by = List(RateLimitDimension.Client),
      limits = List(RateLimitWindow(limit = 1, windowSeconds = 3600)),
      mode = mode,
    )

  private val keys = RateLimitKeys(client = "web")

  def spec = suite("RateLimitPolicyApplier")(
    test("a policy set to enforce is applied as shadow: nothing is refused") {
      for
        limiter <- RateLimiter.make()
        result <- RateLimitPolicyApplier(List(policy("enforced", GuardMode.Enforce)), limiter)
        decisions = (1 to 3).map(_ => limiter.check(Checkpoint.Identity, keys, 0L))
      yield assertTrue(
        result == Right(()),
        decisions.forall(_ == RateDecision.Allowed),
        limiter.stats("enforced").map(s => (s.allowed, s.limited, s.wouldLimit)) == Some((1L, 0L, 2L)),
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
  ) @@ TestAspect.silentLogging

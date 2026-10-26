package versola.util.http

import zio.*
import zio.metrics.{Metric, MetricLabel, MetricState}
import zio.test.*

import java.lang.System as JavaSystem
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.util.Random

/** Time is a number the tests pass in, so the arithmetic is checked exactly and nothing sleeps. */
object RateLimiterSpec extends ZIOSpecDefault:

  private val Second = 1_000_000_000L

  private def policy(
      name: String = "p",
      limits: List[RateLimitWindow],
      burst: Int = 1,
      cost: Int = 1,
      mode: GuardMode = GuardMode.Enforce,
      by: List[RateLimitDimension] = List(RateLimitDimension.Client),
      scope: RateLimitScope = RateLimitScope.EdgeWide,
  ) = RateLimitPolicy(name = name, by = by, scope = scope, limits = limits, burst = burst, cost = cost, mode = mode)

  private def limiter(policies: RateLimitPolicy*)(maxKeys: Int = 1000): UIO[RateLimiter] =
    RateLimiter.make(RateLimiterSettings(maxKeys)).tap(_.update(policies.toList))

  private def client(id: String) = RateLimitKeys(client = id)

  private def counter(policy: String, result: String): UIO[Double] =
    Metric.counter("edge_rate_limit_decisions_total").tagged(Set(MetricLabel("policy", policy), MetricLabel("result", result))).value.map(_.count)

  /** The obvious implementation: a token bucket per window in floating point, all windows or none. */
  private final class Reference(windows: List[(Int, Int)], burst: Int, cost: Int):
    private val tokens = windows.map(_ => burst.toDouble).toArray
    private var last = 0L

    def admit(now: Long): Boolean =
      val elapsed = (now - last).toDouble / Second
      last = now
      val refilled = windows.zipWithIndex.map: (w, i) =>
        math.min(burst.toDouble, tokens(i) + elapsed * w._1 / w._2)
      refilled.indices.foreach(i => tokens(i) = refilled(i))
      val ok = refilled.forall(_ >= cost)
      if ok then refilled.indices.foreach(i => tokens(i) -= cost)
      ok

  // limits whose window divides evenly into nanoseconds, so the integer arithmetic and the model agree exactly
  private val shapes: List[(List[(Int, Int)], Int, Int)] = List(
    (List((10, 1)), 1, 1),
    (List((10, 1)), 5, 1),
    (List((100, 10)), 10, 3),
    (List((5, 1), (20, 10)), 5, 1),
    (List((50, 1), (200, 10), (1000, 600)), 10, 2),
    (List((1, 60)), 1, 1),
  )

  def spec = suite("RateLimiter")(
    suite("arithmetic")(
      test("a quiet key may send `burst` requests together, then is spaced at the rate") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(10, 1)), burst = 3))()
          k = client("a")
          first = (1 to 3).map(_ => l.check(Checkpoint.Identity, k, 0L))
          fourth = l.check(Checkpoint.Identity, k, 0L)
          // 10/s is one token per 100 ms
          early = l.check(Checkpoint.Identity, k, 99_000_000L)
          onTime = l.check(Checkpoint.Identity, k, 100_000_000L)
        yield assertTrue(
          first.forall(_ == RateDecision.Allowed),
          fourth == RateDecision.Limited("p", 1),
          early == RateDecision.Limited("p", 1),
          onTime == RateDecision.Allowed,
        )
      },
      test("Retry-After is in whole seconds, rounded up") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(2, 3)), burst = 1))() // one token per 1.5 s
          k = client("a")
          _ = l.check(Checkpoint.Identity, k, 0L)
          at0 = l.check(Checkpoint.Identity, k, 0L)
          at1 = l.check(Checkpoint.Identity, k, 600_000_000L)   // 0.9 s to go
          at2 = l.check(Checkpoint.Identity, k, 1_400_000_000L) // 0.1 s to go
        yield assertTrue(
          at0 == RateDecision.Limited("p", 2),
          at1 == RateDecision.Limited("p", 1),
          at2 == RateDecision.Limited("p", 1),
        )
      },
      test("a heavier request takes more of the bucket") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(10, 1)), burst = 6, cost = 3))()
          k = client("a")
          r = (1 to 3).map(_ => l.check(Checkpoint.Identity, k, 0L))
        yield assertTrue(r(0) == RateDecision.Allowed, r(1) == RateDecision.Allowed, r(2) == RateDecision.Limited("p", 1))
      },
      test("keys do not share a bucket") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(1, 60))))()
          a = l.check(Checkpoint.Identity, client("a"), 0L)
          b = l.check(Checkpoint.Identity, client("b"), 0L)
          a2 = l.check(Checkpoint.Identity, client("a"), 0L)
        yield assertTrue(a == RateDecision.Allowed, b == RateDecision.Allowed, a2 == RateDecision.Limited("p", 60))
      },
      test("agrees with a token-bucket model on random traffic, for one window and for several") {
        val outcomes = shapes.zipWithIndex.map { case ((windows, burst, cost), n) =>
          val rng = new Random(42 + n)
          val p = policy(
            name = s"model$n",
            limits = windows.map((l, w) => RateLimitWindow(l, w)),
            burst = burst,
            cost = cost,
          )
          for
            l <- limiter(p)()
            model = new Reference(windows, burst, cost)
            // gaps from nothing up to two requests' worth of spacing, in nanoseconds, so every regime is visited
            spacing = windows.map((l, w) => w * Second / l).min
            times = Iterator.iterate(1L)(t => t + 1 + (rng.nextDouble() * 2 * spacing * cost).toLong).take(20_000).toList
            decisions = times.map(t => l.check(Checkpoint.Identity, client("k"), t) == RateDecision.Allowed)
            expected = times.map(model.admit)
          yield (decisions == expected, decisions.count(identity), decisions.count(!_))
        }
        ZIO.foreach(outcomes)(identity).map: results =>
          assertTrue(results.forall(_._1), results.forall(_._2 > 100), results.forall(_._3 > 100))
      },
      test("several windows: the slow one refuses what the fast one would have let through") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(100, 1), RateLimitWindow(5, 60)), burst = 5))()
          k = client("a")
          burst = (1 to 5).map(i => l.check(Checkpoint.Identity, k, i * 1_000_000L))
          // the 1-second window has refilled, the 60-second one has not (one token per 12 s)
          later = l.check(Checkpoint.Identity, k, 3 * Second)
        yield assertTrue(burst.forall(_ == RateDecision.Allowed), later == RateDecision.Limited("p", 10))
      },
    ),
    suite("all or nothing")(
      test("a request refused by one policy takes nothing from the others") {
        // A: ten per second per client, burst 3. B: strictly one per minute per subject.
        val a = policy(name = "a", limits = List(RateLimitWindow(10, 1)), burst = 3, by = List(RateLimitDimension.Client))
        val b = policy(name = "b", limits = List(RateLimitWindow(1, 60)), by = List(RateLimitDimension.Subject))
        for
          l <- limiter(a, b)()
          first = l.check(Checkpoint.Identity, RateLimitKeys(client = "c", subject = "s1"), 0L)
          // the same subject again and again: B refuses each, and A must not be charged for them
          flood = (1 to 50).map(_ => l.check(Checkpoint.Identity, RateLimitKeys(client = "c", subject = "s1"), 0L))
          // A has used one of three tokens: two other subjects fit, a third does not
          two = (2 to 3).map(i => l.check(Checkpoint.Identity, RateLimitKeys(client = "c", subject = s"s$i"), 0L))
          third = l.check(Checkpoint.Identity, RateLimitKeys(client = "c", subject = "s4"), 0L)
          stats = l.stats("a")
        yield assertTrue(
          first == RateDecision.Allowed,
          flood.forall(_ == RateDecision.Limited("b", 60)),
          two.forall(_ == RateDecision.Allowed),
          third == RateDecision.Limited("a", 1),
          stats.map(_.allowed) == Some(3L),
        )
      },
      test("the refusal names the policy that makes the caller wait longest") {
        val a = policy(name = "a", limits = List(RateLimitWindow(1, 2)), by = List(RateLimitDimension.Client))
        val b = policy(name = "b", limits = List(RateLimitWindow(1, 30)), by = List(RateLimitDimension.Subject))
        for
          l <- limiter(a, b)()
          keys = RateLimitKeys(client = "c", subject = "s")
          _ = l.check(Checkpoint.Identity, keys, 0L)
        yield assertTrue(l.check(Checkpoint.Identity, keys, 0L) == RateDecision.Limited("b", 30))
      },
      test("two windows of one policy are all or nothing too") {
        // the slow window refuses; the fast one must not have been charged
        for
          l <- limiter(policy(limits = List(RateLimitWindow(2, 1), RateLimitWindow(1, 60)), burst = 1))()
          k = client("a")
          _ = l.check(Checkpoint.Identity, k, 0L)
          refused = (1 to 10).map(_ => l.check(Checkpoint.Identity, k, 600_000_000L))
          // 60 s later both are back; had the refusals charged the 1 s window it would still be in debt
          ok = l.check(Checkpoint.Identity, k, 61 * Second)
        yield assertTrue(refused.forall(_.isInstanceOf[RateDecision.Limited]), ok == RateDecision.Allowed)
      },
    ),
    suite("modes")(
      test("shadow refuses nothing and counts what enforce would refuse") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(1, 60)), mode = GuardMode.Shadow))()
          r = (1 to 5).map(_ => l.check(Checkpoint.Identity, client("a"), 0L))
        yield assertTrue(r.forall(_ == RateDecision.Allowed), l.stats("p").contains(RateLimitStats(1, 0, 4, 0, 1)))
      },
      test("what shadow counts as would_limit is exactly what enforce then refuses") {
        val rng = new Random(7)
        val times = Iterator.iterate(0L)(t => t + (rng.nextDouble() * 0.4 * Second).toLong).take(5000).toList
        val windows = List(RateLimitWindow(5, 1), RateLimitWindow(20, 10))
        for
          shadow <- limiter(policy(name = "s", limits = windows, burst = 2, mode = GuardMode.Shadow))()
          enforce <- limiter(policy(name = "e", limits = windows, burst = 2, mode = GuardMode.Enforce))()
          keys = RateLimitKeys(client = "a")
          s = times.map(t => shadow.check(Checkpoint.Identity, keys, t))
          e = times.map(t => enforce.check(Checkpoint.Identity, keys, t))
        yield assertTrue(
          s.forall(_ == RateDecision.Allowed),
          shadow.stats("s").map(_.wouldLimit) == enforce.stats("e").map(_.limited),
          shadow.stats("s").map(_.allowed) == enforce.stats("e").map(_.allowed),
          e.exists(_ != RateDecision.Allowed),
        )
      },
      test("a policy that is off does not apply") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(1, 60)), mode = GuardMode.Off))()
        yield assertTrue(!l.active(Checkpoint.Identity), l.check(Checkpoint.Identity, client("a"), 0L) == RateDecision.Allowed)
      },
      test("with no policy nothing applies, and nothing is counted") {
        for
          l <- RateLimiter.make()
        yield assertTrue(
          !l.active(Checkpoint.Identity),
          !l.active(Checkpoint.Route),
          l.check(Checkpoint.Identity, client("a"), 0L) == RateDecision.Allowed,
          l.names.isEmpty,
        )
      },
    ),
    suite("which policies apply where")(
      test("each checkpoint sees only its own policies") {
        val identity = policy(name = "i", limits = List(RateLimitWindow(1, 60)))
        val route = policy(name = "r", limits = List(RateLimitWindow(1, 60)), by = Nil, scope = RateLimitScope.Endpoint)
        for
          l <- limiter(identity, route)()
          keys = RateLimitKeys(client = "c", endpoint = "GET /x")
          _ = l.check(Checkpoint.Identity, keys, 0L)
          again = l.check(Checkpoint.Identity, keys, 0L)
          routeFirst = l.check(Checkpoint.Route, keys, 0L)
        yield assertTrue(
          l.active(Checkpoint.Identity),
          l.active(Checkpoint.Route),
          !l.active(Checkpoint.Address),
          again == RateDecision.Limited("i", 60),
          routeFirst == RateDecision.Allowed,
          l.stats("r").map(_.allowed) == Some(1L),
        )
      },
      test("a scope keeps one bucket per resource or endpoint") {
        val p = policy(limits = List(RateLimitWindow(1, 60)), by = List(RateLimitDimension.Subject), scope = RateLimitScope.Resource)
        for
          l <- limiter(p)()
          a1 = l.check(Checkpoint.Route, RateLimitKeys(subject = "s", resource = "orders"), 0L)
          b1 = l.check(Checkpoint.Route, RateLimitKeys(subject = "s", resource = "billing"), 0L)
          a2 = l.check(Checkpoint.Route, RateLimitKeys(subject = "s", resource = "orders"), 0L)
        yield assertTrue(a1 == RateDecision.Allowed, b1 == RateDecision.Allowed, a2 == RateDecision.Limited("p", 60))
      },
      test("a request without a value the policy needs is not counted against a shared bucket") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(1, 60)), by = List(RateLimitDimension.Session)))()
          none = (1 to 5).map(_ => l.check(Checkpoint.Identity, RateLimitKeys(client = "c"), 0L))
        yield assertTrue(none.forall(_ == RateDecision.Allowed), l.stats("p").contains(RateLimitStats(0, 0, 0, 0, 0)))
      },
      test("a policy with no dimensions is one bucket for the whole scope") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(1, 60)), by = Nil))()
          a = l.check(Checkpoint.Identity, client("a"), 0L)
          b = l.check(Checkpoint.Identity, client("b"), 0L)
        yield assertTrue(a == RateDecision.Allowed, b == RateDecision.Limited("p", 60))
      },
      test("keys made of several values cannot collide by where the values are split") {
        val p = policy(limits = List(RateLimitWindow(1, 60)), by = List(RateLimitDimension.Client, RateLimitDimension.Subject))
        for
          l <- limiter(p)()
          a = l.check(Checkpoint.Identity, RateLimitKeys(client = "ab", subject = "c"), 0L)
          b = l.check(Checkpoint.Identity, RateLimitKeys(client = "a", subject = "bc"), 0L)
          c = l.check(Checkpoint.Identity, RateLimitKeys(client = "1:a", subject = "b"), 0L)
        yield assertTrue(a == RateDecision.Allowed, b == RateDecision.Allowed, c == RateDecision.Allowed)
      },
    ),
    suite("memory")(
      test("a key that has been quiet long enough is dropped") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(10, 1)), burst = 5))()
          _ = (1 to 100).foreach(i => l.check(Checkpoint.Identity, client(s"k$i"), 0L))
          before = l.stats("p").map(_.trackedKeys)
          // the bucket of a key that sent 1 request refills in 100 ms
          _ <- l.sweep(50_000_000L)
          still = l.stats("p").map(_.trackedKeys)
          _ <- l.sweep(150_000_000L)
          after = l.stats("p").map(_.trackedKeys)
        yield assertTrue(before == Some(100), still == Some(100), after == Some(0))
      },
      test("a dropped key starts again with a full bucket, a busy one is kept") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(1, 10)), burst = 1))()
          _ = l.check(Checkpoint.Identity, client("idle"), 0L)
          _ = l.check(Checkpoint.Identity, client("busy"), 9 * Second)
          _ <- l.sweep(10 * Second)
          kept = l.stats("p").map(_.trackedKeys)
          back = l.check(Checkpoint.Identity, client("idle"), 10 * Second)
          busy = l.check(Checkpoint.Identity, client("busy"), 10 * Second)
        yield assertTrue(kept == Some(1), back == RateDecision.Allowed, busy == RateDecision.Limited("p", 9))
      },
      test("past the cap, new keys share an overflow bucket and the map stops growing") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(1, 60)), burst = 1))(maxKeys = 10)
          results = (1 to 1000).map(i => l.check(Checkpoint.Identity, client(s"k$i"), 0L))
          stats = l.stats("p")
        yield assertTrue(
          stats.map(_.trackedKeys) == Some(10),
          // ten keys with their own buckets, and one token for everything else
          results.count(_ == RateDecision.Allowed) == 11,
          stats.map(_.overflowHits) == Some(990L),
          // a key that is already tracked is unaffected by the flood
          l.check(Checkpoint.Identity, client("k1"), 0L) == RateDecision.Limited("p", 60),
        )
      },
      test("a flood of distinct keys frees room again once it is over") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(10, 1)), burst = 1))(maxKeys = 10)
          _ = (1 to 1000).foreach(i => l.check(Checkpoint.Identity, client(s"k$i"), 0L))
          after = l.check(Checkpoint.Identity, client("new"), 5 * Second) // lookup sweeps when at the cap
        yield assertTrue(after == RateDecision.Allowed, l.stats("p").map(_.trackedKeys) == Some(1))
      },
      test("changing an unrelated policy keeps the buckets; changing the limits starts them again") {
        val p = policy(limits = List(RateLimitWindow(1, 60)))
        for
          l <- limiter(p)()
          _ = l.check(Checkpoint.Identity, client("a"), 0L)
          _ <- l.update(List(p.copy(mode = GuardMode.Enforce), policy(name = "other", limits = List(RateLimitWindow(1, 1)))))
          kept = l.check(Checkpoint.Identity, client("a"), 0L)
          _ <- l.update(List(p.copy(limits = List(RateLimitWindow(2, 60)))))
          fresh = l.check(Checkpoint.Identity, client("a"), 0L)
          _ <- l.update(Nil)
        yield assertTrue(kept == RateDecision.Limited("p", 60), fresh == RateDecision.Allowed, l.names.isEmpty)
      },
    ),
    suite("concurrency")(
      test("with the clock stopped, many threads on one key admit exactly the burst and not one more") {
        val burst = 50
        val threads = 16
        for
          l <- limiter(policy(limits = List(RateLimitWindow(100, 1)), burst = burst))()
          admitted <- ZIO.attemptBlocking(hammer(threads, 20_000)(_ => l.check(Checkpoint.Identity, client("hot"), 0L)))
        yield assertTrue(admitted == burst.toLong)
      },
      test("with the clock stopped, several windows and a heavier cost still admit exactly what the tightest allows") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(100, 1), RateLimitWindow(30, 60)), burst = 30, cost = 3))()
          admitted <- ZIO.attemptBlocking(hammer(16, 10_000)(_ => l.check(Checkpoint.Identity, client("hot"), 0L)))
        yield assertTrue(admitted == 10L)
      },
      test("under real time, a key never gets more than its burst plus what the rate allows over the run") {
        // The one test that reads the machine's clock: threads cannot be driven by TestClock. The bound has no slack for
        // scheduling: it is the theoretical maximum, so a lost update would show as a failure, a slow machine would not.
        val rate = 2000
        val burst = 20
        for
          l <- limiter(policy(limits = List(RateLimitWindow(rate, 1)), burst = burst))()
          start = JavaSystem.nanoTime()
          admitted <- ZIO.attemptBlocking(hammerFor(16, 300)(_ => l.check(Checkpoint.Identity, client("hot"), JavaSystem.nanoTime())))
          elapsed = (JavaSystem.nanoTime() - start).toDouble / Second
          most = burst + rate * elapsed
        yield assertTrue(admitted.toDouble <= most + 1, admitted >= burst, elapsed >= 0.3)
      },
      test("two windows under real time: neither is exceeded") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(5000, 1), RateLimitWindow(500, 10)), burst = 10))()
          start = JavaSystem.nanoTime()
          admitted <- ZIO.attemptBlocking(hammerFor(16, 300)(_ => l.check(Checkpoint.Identity, client("hot"), JavaSystem.nanoTime())))
          elapsed = (JavaSystem.nanoTime() - start).toDouble / Second
        yield assertTrue(admitted.toDouble <= 10 + 500.0 / 10 * elapsed + 1, admitted >= 10)
      },
      test("keys inserted while another thread sweeps are all admitted once, and the count ends right") {
        for
          l <- limiter(policy(limits = List(RateLimitWindow(1, 3600)), burst = 1))(maxKeys = 1_000_000)
          admitted <- ZIO.attemptBlocking:
            // The sweeper's clock is far ahead of the checks', so it removes entries under the other threads' hands: a charge
            // may land on a removed entry and must then be redone on a fresh one rather than lost.
            val sweeper = new Thread(() => (1 to 200).foreach(_ => l.sweepNow(Long.MaxValue / 2)))
            sweeper.start()
            val won = hammerKeys(8, 5000)(key => l.check(Checkpoint.Identity, client(key), 0L))
            sweeper.join()
            won
          _ <- l.sweep(Long.MaxValue / 2)
        yield assertTrue(admitted >= 5000L, l.stats("p").map(_.trackedKeys) == Some(0))
      },
    ),
    suite("metrics")(
      test("publish moves the counts to edge_rate_limit_decisions_total by policy and result, once") {
        for
          l <- limiter(policy(name = "metrics-a", limits = List(RateLimitWindow(1, 60))))()
          _ = (1 to 4).foreach(_ => l.check(Checkpoint.Identity, client("secret-key"), 0L))
          _ <- l.publish
          _ <- l.publish // publishing twice must not count twice
          allowed <- counter("metrics-a", "allowed")
          limited <- counter("metrics-a", "limited")
          would <- counter("metrics-a", "would_limit")
        yield assertTrue(allowed == 1.0, limited == 3.0, would == 0.0)
      },
      test("shadow counts would_limit, and the overflow and tracked-keys metrics follow") {
        for
          l <- limiter(policy(name = "metrics-b", limits = List(RateLimitWindow(1, 60)), mode = GuardMode.Shadow))(maxKeys = 2)
          _ = (1 to 6).foreach(i => l.check(Checkpoint.Identity, client(s"k$i"), 0L))
          _ <- l.publish
          would <- counter("metrics-b", "would_limit")
          overflow <- Metric.counter("edge_rate_limit_overflow_hits_total").tagged(MetricLabel("policy", "metrics-b")).value
          tracked <- Metric.gauge("edge_rate_limit_tracked_keys").tagged(MetricLabel("policy", "metrics-b")).value
        yield assertTrue(
          would == 3.0, // 4 overflow requests share one bucket: one token, three refused
          overflow == MetricState.Counter(4.0),
          tracked == MetricState.Gauge(2.0),
        )
      },
    ),
  )

  /** `threads` threads each making `calls` checks; how many were admitted. */
  private def hammer(threads: Int, calls: Int)(check: Int => RateDecision): Long =
    val admitted = new AtomicLong
    val pool = Executors.newFixedThreadPool(threads)
    val start = new CountDownLatch(1)
    (0 until threads).foreach: t =>
      pool.execute: () =>
        start.await()
        var i = 0
        while i < calls do
          if check(t) == RateDecision.Allowed then admitted.incrementAndGet()
          i += 1
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(60, TimeUnit.SECONDS)
    admitted.get

  /** The same, for a stretch of wall-clock time. */
  private def hammerFor(threads: Int, millis: Long)(check: Int => RateDecision): Long =
    val admitted = new AtomicLong
    val pool = Executors.newFixedThreadPool(threads)
    val start = new CountDownLatch(1)
    val until = JavaSystem.nanoTime() + millis * 1_000_000L + 50_000_000L
    (0 until threads).foreach: t =>
      pool.execute: () =>
        start.await()
        while JavaSystem.nanoTime() < until do if check(t) == RateDecision.Allowed then admitted.incrementAndGet()
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(60, TimeUnit.SECONDS)
    admitted.get

  /** `threads` threads each checking every one of `keys` keys once; how many checks were admitted overall. */
  private def hammerKeys(threads: Int, keys: Int)(check: String => RateDecision): Long =
    val admitted = new AtomicLong
    val pool = Executors.newFixedThreadPool(threads)
    val start = new CountDownLatch(1)
    (0 until threads).foreach: _ =>
      pool.execute: () =>
        start.await()
        var i = 0
        while i < keys do
          if check(s"key$i") == RateDecision.Allowed then admitted.incrementAndGet()
          i += 1
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(60, TimeUnit.SECONDS)
    admitted.get

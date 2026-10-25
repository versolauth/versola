package versola.util.http

import zio.*
import zio.test.*

import scala.util.Random

object AdaptiveLimitSpec extends ZIOSpecDefault:

  private val settings = AdaptiveSettings(minLimit = 8, maxLimit = 400, tolerance = 1.5, smoothing = 0.2, lagTarget = 50.millis)
  private val ms = 1_000_000.0

  /** `n` windows of the same kind of traffic, three classes, only the first used. */
  private def run(
      from: AdaptiveState,
      windows: Int,
      rtt: Double,
      inFlight: Int,
      lag: Duration = Duration.Zero,
      completed: Int = 100,
  ) =
    (1 to windows).foldLeft(from): (state, _) =>
      AdaptiveLimit.step(
        settings,
        state,
        Vector(RttWindow(rtt, completed), RttWindow(0, 0), RttWindow(0, 0)),
        inFlight,
        lag,
      )

  private val start = AdaptiveLimit.initial(settings, classes = 3, start = 400)

  /** One window of a trace: what the pod saw in that second. */
  private final case class Second(rtt: Double, inFlight: Int, lag: Duration = Duration.Zero, completed: Int = 100)

  private def stepOn(settings: AdaptiveSettings, state: AdaptiveState, w: Second) =
    AdaptiveLimit.step(
      settings,
      state,
      Vector(RttWindow(w.rtt, w.completed), RttWindow(0, 0), RttWindow(0, 0)),
      w.inFlight,
      w.lag,
    )

  /** The state after each window of `trace`, the first being the state after the first window. */
  private def states(settings: AdaptiveSettings, from: AdaptiveState, trace: Seq[Second]): Seq[AdaptiveState] =
    trace.scanLeft(from)((state, w) => stepOn(settings, state, w)).tail

  private val production = AdaptiveSettings.default

  /** The measured evidence: in-flight 20-60 a second, call times a few percent around 20 ms, with a GC-like pause window now and then. */
  private def healthyTrace(seed: Long, windows: Int, spikeEvery: Int = 0): Seq[Second] =
    val random = new Random(seed)
    (1 to windows).map: i =>
      val noise = 1.0 + (random.nextDouble() - 0.5) * 0.4 // 0.8 - 1.2, inside the 1.5 tolerance
      val spike = if spikeEvery > 0 && i % spikeEvery == 0 then 3.0 else 1.0
      Second(rtt = 20 * ms * noise * spike, inFlight = 20 + random.nextInt(41))

  private def stallTrace(windows: Int) = Seq.fill(windows)(Second(rtt = 200 * ms, inFlight = 500, lag = 150.millis))

  def spec = suite("AdaptiveLimit")(
    suite("headroom")(
      test("healthy traffic with in-flight wandering 20-60 never leaves the limit under 1.5 x the recent envelope, and nothing is refused") {
        val start = AdaptiveLimit.initial(production, classes = 3, start = 256)
        val trace = healthyTrace(seed = 1, windows = 600)
        val after = states(production, start, trace)
        val steady = after.zip(trace).drop(60)
        // refused in a simulated admission: a window whose peak is above the limit in force during it (the state before it)
        val refused = (start +: after).zip(trace).count((before, w) => w.inFlight > before.limit.toInt)
        assertTrue(
          steady.forall((s, _) => s.limit >= AdaptiveLimit.HeadroomFactor * s.envelope - 1e-9),
          // the envelope tracks what was carried: it is never under the decayed peak of the last 10 windows
          steady.zip(trace.drop(60)).forall { case ((s, _), _) => s.envelope >= 40 },
          steady.forall((s, w) => s.limit >= 1.5 * w.inFlight),
          refused == 0,
        )
      },
      test("after a slow stretch has cut the limit, healthy traffic of the same shape is restored to its headroom at once and refused nowhere") {
        val start = AdaptiveLimit.initial(production, classes = 3, start = 256)
        val warm = states(production, start, healthyTrace(seed = 2, windows = 120)).last
        val slow = states(production, warm, Seq.fill(8)(Second(rtt = 70 * ms, inFlight = 55)))
        val trace = healthyTrace(seed = 6, windows = 300)
        val after = states(production, slow.last, trace)
        val refused = (slow.last +: after).zip(trace).count((before, w) => w.inFlight > before.limit.toInt)
        assertTrue(
          // the slow stretch really took the limit down into the range of normal in-flight
          slow.last.limit < 60,
          after.forall(s => s.limit >= AdaptiveLimit.HeadroomFactor * s.envelope - 1e-9),
          after.head.limit >= 1.5 * 40,
          refused == 0,
        )
      },
      test("a real slowdown (two slow windows in a row) cuts below the floor, and the next healthy window restores it") {
        val start = AdaptiveLimit.initial(production, classes = 3, start = 256)
        // a limit near its floor, as after earlier cuts; the envelope of healthy traffic is about 55
        val warm = states(production, start, healthyTrace(seed = 3, windows = 100)).last.copy(limit = 90)
        val cut = states(production, warm, Seq.fill(4)(Second(rtt = 100 * ms, inFlight = 55))).last
        val back = stepOn(production, cut, Second(rtt = 20 * ms, inFlight = 55))
        assertTrue(
          cut.limit < AdaptiveLimit.headroom(production, warm),
          back.limit >= AdaptiveLimit.headroom(production, back),
          back.limit >= 1.5 * 40,
        )
      },
      test("a lone slow window holds the limit; the second in a row decreases it") {
        val start = AdaptiveLimit.initial(production, classes = 3, start = 256)
        val warm = states(production, start, Seq.fill(60)(Second(rtt = 20 * ms, inFlight = 200))).last
        val one = stepOn(production, warm, Second(rtt = 100 * ms, inFlight = 200))
        val two = stepOn(production, one, Second(rtt = 100 * ms, inFlight = 200))
        assertTrue(one.limit == warm.limit, two.limit < 0.8 * warm.limit)
      },
      test("a stall pulls the limit to the floor within a few windows, and it recovers when the signals clear") {
        val start = AdaptiveLimit.initial(production, classes = 3, start = 256)
        val warm = states(production, start, healthyTrace(seed = 4, windows = 120)).last
        val stall = states(production, warm, stallTrace(20))
        val firstBelow2Min = stall.indexWhere(_.limit < 2 * production.minLimit) + 1
        // the same stall from a limit of 90, about what the limit was without headroom (mean 88 in the measured run)
        val fromLow = states(production, warm.copy(limit = 90), stallTrace(20))
        val lowBelow2Min = fromLow.indexWhere(_.limit < 2 * production.minLimit) + 1
        val healed = states(production, stall.last, Seq.fill(120)(Second(rtt = 20 * ms, inFlight = 40)))
        // measured: windows until under 2 x min (32) from 256 and from 90, limit after 20 stalled windows, first healed window
        assertTrue(
          firstBelow2Min > 0,
          firstBelow2Min <= 10,
          lowBelow2Min > 0,
          lowBelow2Min <= 6,
          stall.last.limit == production.minLimit.toDouble,
          healed.head.limit >= 1.5 * 30,
          healed.last.limit >= 1.5 * 40,
          healed.last.limit > stall.last.limit * 3,
        )
      },
      test("a stall's pile-up is not what the pod carries: the envelope does not take it in") {
        val start = AdaptiveLimit.initial(production, classes = 3, start = 256)
        val warm = states(production, start, healthyTrace(seed = 5, windows = 120)).last
        val stall = states(production, warm, stallTrace(20)).last
        // and clearing it does not jump the limit to 1.5 x 500
        val first = stepOn(production, stall, Second(rtt = 20 * ms, inFlight = 500))
        assertTrue(stall.envelope < warm.envelope, first.limit < 200)
      },
      test("the envelope decays when load drops, and the floor follows it down") {
        val start = AdaptiveLimit.initial(production, classes = 3, start = 256)
        val busy = states(production, start, Seq.fill(120)(Second(rtt = 20 * ms, inFlight = 60))).last
        val quiet = states(production, busy, Seq.fill(120)(Second(rtt = 20 * ms, inFlight = 5)))
        val at60 = quiet(59)
        val floorBusy = AdaptiveLimit.headroom(production, busy)
        assertTrue(
          floorBusy == 90.0,
          // a time constant of a minute: after 60 windows about 1/e of 60, after 120 about 1/e^2
          math.abs(at60.envelope - 60 / math.E) < 2.0,
          quiet.last.envelope < 10.0,
          AdaptiveLimit.headroom(production, quiet.last) < 0.2 * floorBusy,
          quiet.map(_.envelope).sliding(2).forall(w => w(1) <= w(0)),
        )
      },
      test("a peak pinned at the limit is not folded in: the floor cannot lift the limit by itself") {
        val start = AdaptiveLimit.initial(production, classes = 3, start = 100)
        // healthy call times, in-flight exactly at the limit every window: only the slow probe may raise it
        val after = states(production, start, Seq.fill(10)(Second(rtt = 20 * ms, inFlight = 100)))
        assertTrue(after.last.envelope == 0.0, after.last.limit < 150.0)
      },
      test("headroom is clamped by the ceiling") {
        val small = AdaptiveSettings.default.copy(minLimit = 4, maxLimit = 50)
        val start = AdaptiveLimit.initial(small, classes = 3, start = 50)
        val after = states(small, start, Seq.fill(30)(Second(rtt = 20 * ms, inFlight = 30)))
        assertTrue(after.last.limit == 50.0, AdaptiveLimit.headroom(small, after.last.copy(envelope = 1000)) == 50.0)
      },
      test("the limit stays inside [minLimit, maxLimit] whatever the trace") {
        check(
          Gen.listOfBounded(1, 200)(
            Gen.double(0, 1e9).zip(Gen.int(0, 5000)).zip(Gen.long(0, 5_000_000_000L)).zip(Gen.int(0, 1000)),
          ),
          Gen.int(1, 64),
          Gen.int(64, 2000),
        ): (trace, min, max) =>
          val bounded = production.copy(minLimit = min, maxLimit = max)
          val seconds = trace.map { case (rtt, inFlight, lag, completed) => Second(rtt, inFlight, Duration.fromNanos(lag), completed) }
          val all = states(bounded, AdaptiveLimit.initial(bounded, classes = 3, start = 256), seconds)
          assertTrue(all.forall(s => s.limit >= min && s.limit <= max && s.envelope >= 0 && !s.limit.isNaN))
      },
    ),
    test("steady call times with the limit in use: the limit holds at the ceiling and never passes it") {
      val s = run(start, 200, 10 * ms, inFlight = 400)
      assertTrue(s.limit == 400.0)
    },
    test("calls that get much slower than they have been shrink the limit, to the floor if they stay slow") {
      val warm = run(start, 60, 10 * ms, inFlight = 400)
      val slow = run(warm, 60, 100 * ms, inFlight = 400)
      assertTrue(slow.limit < 0.5 * warm.limit, slow.limit >= settings.minLimit)
    },
    test("calls within the tolerance of their usual time do not shrink it") {
      val warm = run(start, 60, 10 * ms, inFlight = 400)
      val bit = run(warm, 20, 13 * ms, inFlight = 400)
      assertTrue(bit.limit >= 0.95 * warm.limit)
    },
    test("it recovers: once calls are fast again the limit grows back") {
      val warm = run(start, 60, 10 * ms, inFlight = 400)
      val hurt = run(warm, 40, 100 * ms, inFlight = 400)
      val healed = run(hurt, 80, 10 * ms, inFlight = 400)
      assertTrue(hurt.limit < 100, healed.limit > hurt.limit * 2)
    },
    test("a stalled executor shrinks the limit even though no call completed to measure") {
      val warm = run(start, 60, 10 * ms, inFlight = 400)
      val stalled = run(warm, 10, 0, inFlight = 400, lag = 2.seconds, completed = 0)
      assertTrue(stalled.limit < 0.6 * warm.limit)
    },
    test("a lag below the target changes nothing") {
      val warm = run(start, 60, 10 * ms, inFlight = 400)
      val calm = run(warm, 20, 10 * ms, inFlight = 400, lag = 20.millis)
      assertTrue(calm.limit == warm.limit)
    },
    test("a limit that is far from being used is not raised on that evidence") {
      val low = start.copy(limit = 100.0)
      val s = run(low, 50, 10 * ms, inFlight = 10)
      assertTrue(s.limit == 100.0)
    },
    test("a limit in use grows by probing, slowly, up to the ceiling") {
      val low = start.copy(limit = 100.0)
      val after5 = run(low, 5, 10 * ms, inFlight = 100)
      val after200 = run(low, 200, 10 * ms, inFlight = 400)
      assertTrue(after5.limit > 100.0, after5.limit < 130.0, after200.limit == 400.0)
    },
    test("each class keeps its own long-run level: a mix moving to a slower class is not a slowdown") {
      val fast = (1 to 60).foldLeft(start): (state, _) =>
        AdaptiveLimit.step(settings, state, Vector(RttWindow(5 * ms, 100), RttWindow(0, 0), RttWindow(0, 0)), 400, Duration.Zero)
      // a login class that has always taken 200 ms starts to appear next to the fast one
      val mixed = (1 to 60).foldLeft(fast): (state, _) =>
        AdaptiveLimit.step(settings, state, Vector(RttWindow(5 * ms, 100), RttWindow(0, 0), RttWindow(200 * ms, 10)), 400, Duration.Zero)
      assertTrue(mixed.limit >= 0.9 * fast.limit)
    },
    test("it starts from what it is given, inside the bounds") {
      assertTrue(
        AdaptiveLimit.initial(settings, 3, start = 100).limit == 100.0,
        AdaptiveLimit.initial(settings, 3, start = 1).limit == 8.0,
        AdaptiveLimit.initial(settings, 3, start = 10000).limit == 400.0,
      )
    },
    test("rebound brings a learned limit inside new bounds and leaves it alone inside them") {
      val s = start.copy(limit = 250.0)
      assertTrue(
        AdaptiveLimit.rebound(s, settings.copy(maxLimit = 100)).limit == 100.0,
        AdaptiveLimit.rebound(s, settings.copy(minLimit = 300, maxLimit = 400)).limit == 300.0,
        AdaptiveLimit.rebound(s, settings).limit == 250.0,
      )
    },
  )

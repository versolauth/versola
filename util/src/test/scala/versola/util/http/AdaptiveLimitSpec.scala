package versola.util.http

import zio.*
import zio.test.*

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

  def spec = suite("AdaptiveLimit")(
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

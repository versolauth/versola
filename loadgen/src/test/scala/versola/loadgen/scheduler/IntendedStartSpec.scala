package versola.loadgen.scheduler

import zio.Duration
import zio.durationInt
import zio.test.*

import java.time.Instant

/** The intended-start half of §7.2: that latency is measured from the schedule, and that a driver
  * which has fallen behind can report it.
  */
object IntendedStartSpec extends ZIOSpecDefault:

  private val intended = Instant.parse("2026-09-10T12:00:00Z")

  def spec = suite("IntendedStart")(
    suite("measurement")(
      test("latency spans the driver's own queueing delay, not just the request") {
        val actualStart = intended.plusMillis(400)
        val completed = actualStart.plusMillis(60)
        assertTrue(
          IntendedStart.latency(intended, completed) == 460.millis,
          IntendedStart.startDelay(intended, actualStart) == 400.millis,
          // The number a closed-loop driver would have reported instead, and the reason §7.2
          // insists on the intended start: the run looks 7.7x faster than it was.
          Duration.fromInterval(actualStart, completed) == 60.millis,
        )
      },
      test("a start delay is floored at zero rather than reported negative") {
        assertTrue(IntendedStart.startDelay(intended, intended.minusMillis(5)) == Duration.Zero)
      },
    ),
    suite("ScheduleLag")(
      test("reports now minus the intended start of the head of the queue") {
        for
          lag <- ScheduleLag.make
          drained <- lag.lagAt(intended.plusSeconds(30))
          _ <- lag.observeHead(Some(intended))
          onTime <- lag.lagAt(intended)
          behind <- lag.lagAt(intended.plusMillis(300))
          head <- lag.headIntendedStart
        yield assertTrue(
          drained == Duration.Zero,
          onTime == Duration.Zero,
          // Above 250 ms §7.2 declares the driver the bottleneck and the run invalid; this is
          // the value track F's `loadgen_schedule_lag_seconds` gauge must surface.
          behind == 300.millis,
          behind.toMillis > 250L,
          head == Some(intended),
        )
      },
      test("a drained queue reports zero, not the last head it saw") {
        for
          lag <- ScheduleLag.make
          _ <- lag.observeHead(Some(intended))
          _ <- lag.observeHead(None)
          drained <- lag.lagAt(intended.plusSeconds(600))
        yield assertTrue(drained == Duration.Zero)
      },
      test("current reads the clock so the metric collector needs no arguments") {
        for
          lag <- ScheduleLag.make
          now <- zio.Clock.instant
          _ <- lag.observeHead(Some(now.minusMillis(500)))
          current <- lag.current
        yield assertTrue(current.toMillis >= 500L)
      },
    ),
  )

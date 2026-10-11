package versola.loadgen.coordinator

import versola.loadgen.config.CampaignPhaseConfig
import versola.loadgen.metrics.DriverHistogramReport
import zio.Duration
import zio.test.*

import java.time.Instant

object MeasurementWindowSpec extends ZIOSpecDefault:

  private val start = Instant.parse("2026-10-09T12:00:00Z")

  private def phase(name: String, minutes: Long, measured: Boolean) =
    CampaignPhaseConfig(name, Duration.fromSeconds(minutes * 60), Some(1.0), None, None, measured)

  private val phases = List(phase("warmup", 2, false), phase("ramp", 3, false), phase("steady", 10, true))

  private def report(at: Instant) = DriverHistogramReport(1, "c", "driver-0", at.toEpochMilli, Nil)

  def spec = suite("MeasurementWindow")(
    test("lays the unmeasured phases out from the start, in order") {
      assertTrue(
        MeasurementWindow.excluded(phases, Some(start)) ==
          List((start, start.plusSeconds(120)), (start.plusSeconds(120), start.plusSeconds(300))),
      )
    },
    test("an interval is judged by the phase it ended in, and the end of a phase belongs to it") {
      val reports = List(
        report(start.plusSeconds(60)),  // warm-up
        report(start.plusSeconds(120)), // the instant warm-up ends: still warm-up
        report(start.plusSeconds(150)), // ramp
        report(start.plusSeconds(300)), // the instant the ramp ends: still ramp
        report(start.plusSeconds(301)), // steady
        report(start.plusSeconds(900)), // steady
      )
      val kept = MeasurementWindow.measured(reports, MeasurementWindow.excluded(phases, Some(start)))
      assertTrue(kept.map(_.capturedAtEpochMillis) == List(start.plusSeconds(301), start.plusSeconds(900)).map(_.toEpochMilli))
    },
    test("the window runs from the first measured phase to the end of the last") {
      assertTrue(
        MeasurementWindow.bounds(phases, Some(start)) == Some((start.plusSeconds(300), start.plusSeconds(900))),
        MeasurementWindow.bounds(phases, None).isEmpty,
        MeasurementWindow.bounds(phases.map(_.copy(measured = false)), Some(start)).isEmpty,
        MeasurementWindow.bounds(phases.map(_.copy(measured = true)), Some(start)) == Some((start, start.plusSeconds(900))),
      )
    },
    // A coordinator restarted mid-campaign has lost the start with the rest of its memory.
    // Dropping intervals on a guess would be wrong in a way nobody sees; keeping them is wrong in a
    // way the header shows.
    test("an unknown start excludes nothing") {
      val reports = List(report(start), report(start.plusSeconds(60)))
      assertTrue(
        MeasurementWindow.excluded(phases, None).isEmpty,
        MeasurementWindow.measured(reports, MeasurementWindow.excluded(phases, None)) == reports,
      )
    },
    test("every phase measured, as a campaign configured before the flag, keeps everything") {
      val all = phases.map(_.copy(measured = true))
      val reports = List(report(start.plusSeconds(60)), report(start.plusSeconds(500)))
      assertTrue(MeasurementWindow.measured(reports, MeasurementWindow.excluded(all, Some(start))) == reports)
    },
  )

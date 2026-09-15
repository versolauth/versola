package versola.loadgen.coordinator

import versola.loadgen.metrics.{HistogramWire, MeasurementId}
import versola.loadgen.store.MeasurementKind
import zio.test.*

import java.time.Instant

/** The store's rows back into the envelopes the report merges.
  *
  * The interesting property is the grouping: intervals must not be folded together, because the
  * wire version and the sample-count check are stated per envelope, and a merge that regrouped
  * them could double-count an interval without any decode failing.
  */
object SnapshotMergeSpec extends ZIOSpecDefault:

  private val campaign = "c3-10m-steady"

  private val tokenRefresh = MeasurementId.Step("mobile-otp", "token-refresh")

  private val loginFlow = MeasurementId.Flow("mobile-otp-login")

  private val first = Instant.parse("2026-09-15T08:00:00Z")

  private val second = first.plusSeconds(60)

  private def row(driverId: String, at: Instant, id: MeasurementId, micros: Long, count: Long) =
    CoordinatorFixture.snapshotRow(campaign, driverId, at, id, micros, count)

  def spec = suite("SnapshotMerge")(
    test("one envelope per driver and interval, oldest first") {
      val rows = Vector(
        row("driver-1", second, tokenRefresh, 90_000L, 10L),
        row("driver-0", first, tokenRefresh, 80_000L, 10L),
        row("driver-0", first, loginFlow, 300_000L, 10L),
        row("driver-1", first, tokenRefresh, 85_000L, 10L),
      )
      val reports = SnapshotMerge.toReports(rows)
      assertTrue(
        reports.map(_.size) == Right(3),
        reports.map(_.map(report => (report.driverId, report.capturedAtEpochMillis))) == Right(
          List(
            ("driver-0", first.toEpochMilli),
            ("driver-1", first.toEpochMilli),
            ("driver-1", second.toEpochMilli),
          ),
        ),
        reports.map(_.head.histograms.size) == Right(2),
        reports.map(_.forall(_.version == HistogramWire.version)) == Right(true),
      )
    },
    test("the merge sums buckets across drivers and intervals without re-bucketing") {
      val rows = Vector(
        row("driver-0", first, tokenRefresh, 1_000L, 100L),
        row("driver-1", first, tokenRefresh, 2_000L, 100L),
        row("driver-0", second, tokenRefresh, 2_000L, 100L),
      )
      val summaries = SnapshotMerge.summaries(rows)
      assertTrue(
        summaries.map(_.size) == Right(1),
        summaries.map(_.head.count) == Right(300L),
        summaries.map(_.head.p50Micros) == Right(2_000L),
        summaries.map(_.head.minMicros) == Right(1_000L),
        summaries.map(_.head.maxMicros) == Right(2_000L),
      )
    },
    test("a flow keeps its own name and a step keeps its scenario") {
      val rows = Vector(row("driver-0", first, tokenRefresh, 1_000L, 1L), row("driver-0", first, loginFlow, 2_000L, 1L))
      assertTrue(
        SnapshotMerge.summaries(rows).map(_.map(_.id).sortBy(_.toString)) == Right(
          List(loginFlow, tokenRefresh).sortBy(_.toString),
        ),
      )
    },
    test("a step row with no scenario is refused rather than filed under an invented one") {
      val broken = row("driver-0", first, tokenRefresh, 1_000L, 1L).copy(scenario = None)
      assertTrue(
        SnapshotMerge.toReports(Vector(broken)).isLeft,
        SnapshotMerge.summaries(Vector(broken)).isLeft,
        SnapshotMerge.measurementOf(broken).isLeft,
        SnapshotMerge.measurementOf(broken.copy(kind = MeasurementKind.Flow)) == Right(MeasurementId.Flow(
          "token-refresh",
        )),
      )
    },
    test("a payload written by a driver on another wire version is refused, not decoded") {
      val stale = row("driver-0", first, tokenRefresh, 1_000L, 1L).copy(wireVersion = HistogramWire.version + 1)
      assertTrue(SnapshotMerge.summaries(Vector(stale)).isLeft)
    },
    test("an envelope whose declared count does not match its payload is refused") {
      val tampered = row("driver-0", first, tokenRefresh, 1_000L, 10L).copy(sampleCount = 11L)
      assertTrue(SnapshotMerge.summaries(Vector(tampered)).isLeft)
    },
  )

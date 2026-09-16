package versola.loadgen.scenario

import versola.loadgen.metrics.{HistogramSample, HistogramWire, LatencyRecorder, MeasurementId}
import versola.loadgen.store.{MeasurementKind, MetricSnapshotRepository, MetricSnapshotRow}
import zio.*

import java.time.Instant

/** The §11 handoff: every `snapshotInterval`, take and reset this process's histograms and write
  * what holds samples into `vu_metric_snapshots`.
  *
  * This is the only channel the campaign's quantiles travel on. The coordinator merges the rows
  * (`SnapshotMerge`) rather than scraping the drivers, which is what makes
  * `GET /report/{campaign}` survive a coordinator restart and a driver that has gone away -- and
  * it is why the recorder is *reset* on every take: consecutive snapshots have to partition the
  * recorded values, or every sample is counted once per interval it survives into.
  *
  * Shared by `role = driver` and `role = calibrate` because the gate's whole argument is that it
  * measures through the path a campaign measures through -- a second copy of this encoding would
  * be a path the gate does not cover.
  */
final class SnapshotPublisher(
    campaign: String,
    driverId: String,
    latencies: LatencyRecorder,
    snapshots: MetricSnapshotRepository,
):

  /** One interval: take and reset every recorder, and write what actually holds samples.
    *
    * Empty histograms are skipped rather than written as zero-count rows. They would decode and
    * merge correctly, but a measurement that is present and empty is what
    * `CampaignReport.measured` exists to tell apart from one that recorded something.
    */
  def publish: Task[Unit] =
    for
      capturedAt <- Clock.instant
      samples <- latencies.snapshot
      rows = samples.filter(_.histogram.getTotalCount > 0L).map(SnapshotPublisher.rowOf(campaign, driverId, capturedAt, _))
      _ <- snapshots.appendAll(rows)
      _ <- ZIO.logDebug(s"Wrote ${rows.size} snapshot rows for '$campaign' at $capturedAt").when(rows.nonEmpty)
    yield ()

  /** The timer, forked into the caller's scope, plus a final take when that scope closes.
    *
    * The final take is not tidiness: without it the run's last samples are in a recorder nobody
    * read, and the campaign is judged on a partial interval whose missing part is always the same
    * part -- the end of the run, which is where a SUT that degrades under sustained load shows it.
    */
  def run(interval: Duration): ZIO[Scope, Nothing, Unit] =
    ZIO.addFinalizer(publish.catchAllCause(ZIO.logErrorCause("Final metric snapshot failed", _))) *>
      publish
        .catchAllCause(ZIO.logErrorCause("Metric snapshot failed; the interval's samples are lost", _))
        .repeat(Schedule.spaced(interval))
        .forkScoped
        .unit

object SnapshotPublisher:

  /** §11's snapshot interval. */
  val interval: Duration = 60.seconds

  /** Track F's label into the store's `(kind, scenario, name)` columns -- the inverse of
    * `SnapshotMerge.measurementOf`, which is what reads them back.
    */
  def rowOf(campaign: String, driverId: String, capturedAt: Instant, sample: HistogramSample): MetricSnapshotRow =
    val encoded = HistogramWire.encode(sample)
    val (kind, scenario, name) = sample.id match
      case MeasurementId.Step(scenario, step) => (MeasurementKind.Step, Some(scenario), step)
      case MeasurementId.Flow(flow) => (MeasurementKind.Flow, None, flow)
    MetricSnapshotRow(
      campaign = campaign,
      driverId = driverId,
      capturedAt = capturedAt,
      wireVersion = HistogramWire.version,
      kind = kind,
      scenario = scenario,
      name = name,
      unit = encoded.unit,
      sampleCount = encoded.count,
      histogram = encoded.encoding,
    )

package versola.loadgen.coordinator

import versola.loadgen.metrics.{
  DriverHistogramReport,
  EncodedHistogram,
  HistogramSample,
  HistogramWire,
  LatencySummary,
  MeasurementId,
}
import versola.loadgen.store.{MeasurementKind, MetricSnapshotRow}
import zio.Chunk

/** Turns the `vu_metric_snapshots` rows every driver writes every 60 s (dev spec §11) back into
  * the envelopes `CampaignReport.assemble` merges (§12).
  *
  * The snapshots are read from the emulator's store rather than pushed to the coordinator, and
  * that is what makes `GET /report/{campaign}` survive this process: the campaign's measured
  * latency accumulates over hours in the one `LOGGED` table of the schema, and a coordinator that
  * restarts mid-campaign re-reads all of it. A coordinator holding the histograms in memory would
  * make its own uptime a precondition of the deliverable.
  *
  * Rows are grouped back into one envelope per `(driver, captured_at)` -- the shape they were
  * written in -- because that pair is the unit the wire version and the count checks are stated
  * against, and because `HistogramWire.decodeReport` rejects a whole envelope on a version
  * mismatch. Regrouping them any other way would either hide a stale driver's payload inside a
  * current envelope or double-count an interval.
  */
object SnapshotMerge:

  /** @return one envelope per driver and snapshot interval, oldest first, or the first row that
    *         cannot be placed. A row is only unplaceable if the store holds a shape the schema
    *         permits and §11 does not -- a step with no scenario -- which is a driver bug, and
    *         reporting it is strictly better than merging the remaining intervals into a verdict
    *         that looks complete.
    */
  def toReports(rows: Iterable[MetricSnapshotRow]): Either[String, List[DriverHistogramReport]] =
    rows
      .groupBy(row => (row.campaign, row.driverId, row.capturedAt, row.wireVersion))
      .toList
      .sortBy { case ((campaign, driverId, capturedAt, _), _) => (capturedAt, driverId, campaign) }
      .foldLeft[Either[String, List[DriverHistogramReport]]](Right(Nil)):
        case (Left(error), _) => Left(error)
        case (Right(accumulated), ((campaign, driverId, capturedAt, wireVersion), interval)) =>
          encode(interval).map: histograms =>
            accumulated :+ DriverHistogramReport(
              version = wireVersion,
              campaign = campaign,
              driverId = driverId,
              capturedAtEpochMillis = capturedAt.toEpochMilli,
              histograms = histograms,
            )

  private def encode(rows: Iterable[MetricSnapshotRow]): Either[String, List[EncodedHistogram]] =
    rows.foldLeft[Either[String, List[EncodedHistogram]]](Right(Nil)):
      case (Left(error), _) => Left(error)
      case (Right(accumulated), row) =>
        measurementOf(row).map: id =>
          accumulated :+ EncodedHistogram(
            id = id,
            unit = row.unit,
            count = row.sampleCount,
            encoding = row.histogram,
          )

  /** The store's `(kind, scenario, name)` columns back into track F's label.
    *
    * `MeasurementKind.Step` with no scenario is refused rather than filed under a placeholder
    * scenario: §11 labels a step by scenario *and* step, so a merge that invented one would
    * publish a quantile under a scenario that does not exist, and the acceptance threshold naming
    * the real one would land in `notEvaluated` with no indication why.
    */
  def measurementOf(row: MetricSnapshotRow): Either[String, MeasurementId] =
    (row.kind, row.scenario) match
      case (MeasurementKind.Step, Some(scenario)) => Right(MeasurementId.Step(scenario, row.name))
      case (MeasurementKind.Step, None) =>
        Left(s"snapshot from driver ${row.driverId} names step '${row.name}' with no scenario")
      case (MeasurementKind.Flow, _) => Right(MeasurementId.Flow(row.name))

  /** The unit and version checks of [[HistogramWire]], applied to a whole campaign's worth of
    * snapshots. Exposed so `GET /status`'s live quantiles and `GET /report/{campaign}`'s verdict
    * decode the same way -- a payload the report would refuse must not quietly appear on the
    * status page.
    */
  def summaries(rows: Iterable[MetricSnapshotRow]): Either[String, List[LatencySummary]] =
    for
      reports <- toReports(rows)
      samples <- reports.foldLeft[Either[String, Chunk[HistogramSample]]](Right(Chunk.empty)):
        case (Left(error), _) => Left(error)
        case (Right(accumulated), report) => HistogramWire.decodeReport(report).map(accumulated ++ _)
    yield HistogramWire.summarise(HistogramWire.merge(samples)).sortBy(_.id.toString)

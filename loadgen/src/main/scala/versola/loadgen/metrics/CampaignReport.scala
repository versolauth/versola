package versola.loadgen.metrics

import zio.json.JsonCodec
import zio.{Chunk, Duration}

/** An absolute latency ceiling: `p99` of `id` must not exceed this. Backs the design doc's
  * "auth `/token` p99 ≤ 120 ms".
  */
case class LatencyThreshold(id: MeasurementId, p99: Duration)

/** A latency ceiling stated relative to another measurement, plus a margin: backs "edge proxy
  * p99 ≤ backend p99 + 15 ms", which is the one criterion that cannot be evaluated from a single
  * histogram and is therefore the clearest illustration of why the merge has to be lossless -- it
  * compares two campaign-wide p99s, not two averages of pod p99s.
  */
case class RelativeLatencyThreshold(id: MeasurementId, relativeTo: MeasurementId, margin: Duration)

/** What `GET /report/{campaign}` judges the campaign against (design doc §6.7, dev spec §15).
  *
  * No defaults anywhere (versolauth/versola#267): every campaign states its own thresholds, and
  * [[AcceptanceThresholds.designDefaults]] exists for the ones the design doc fixes.
  */
case class AcceptanceThresholds(
    latency: List[LatencyThreshold],
    relativeLatency: List[RelativeLatencyThreshold],
    scheduleLagP99: Duration,
    maxRefreshRejected: Long,
    maxFlushDropped: Long,
    maxDriverCpu: Double,
    maxErrorBudgetRatio: Double,
    maxLatencyClamped: Long,
)

object AcceptanceThresholds:

  /** The figures the design doc fixes. The measurement ids are parameters rather than constants
    * because the scenario/step naming belongs to the scenario engine, and hard-coding a guess at
    * it here would produce a verdict that silently evaluates nothing when the names differ.
    */
  def designDefaults(
      tokenRefresh: MeasurementId,
      edgeProxy: MeasurementId,
      mockBackend: MeasurementId,
  ): AcceptanceThresholds =
    AcceptanceThresholds(
      latency = List(LatencyThreshold(tokenRefresh, Duration.fromMillis(120))),
      relativeLatency = List(RelativeLatencyThreshold(edgeProxy, mockBackend, Duration.fromMillis(15))),
      scheduleLagP99 = Duration.fromMillis(250),
      maxRefreshRejected = 0L,
      maxFlushDropped = 0L,
      maxDriverCpu = 0.4,
      maxErrorBudgetRatio = 0.0,
      // A clamped sample is an instrument failure, not a slow SUT: the reported tail becomes a
      // floor rather than a measurement, and §6.7 rests the whole report's credibility on the
      // recorder being in range. One is enough to fail the campaign. With the driver's request
      // timeout at 30 s against a 60 s recorder ceiling, a clamp is structurally unreachable
      // without a bug in the driver, which is the correct reading of the signal.
      maxLatencyClamped = 0L,
    )

/** Fleet-wide driver-health figures at the end of a campaign, as the coordinator has them.
  *
  * `maxDriverCpu` and `scheduleLagP99` are the worst reading over the fleet, not the mean: one
  * saturated driver distorts the latencies of its own shard, and averaging that away is how a
  * campaign passes while a sixth of its measurements are client-side queueing.
  */
case class CampaignHealth(
    refreshRejectedTotal: Long,
    flushDroppedTotal: Long,
    maxDriverCpu: Option[Double],
    scheduleLagP99: Option[Duration],
    latencyClampedTotal: Long,
) derives JsonCodec

/** One line of the verdict. `detail` carries the measured value so a failed check is actionable
  * without going back to the raw histograms.
  */
case class ReportCheck(name: String, passed: Boolean, detail: String) derives JsonCodec

/** The body of `GET /report/{campaign}` (§12): merged quantiles, the error taxonomy, and the
  * verdict.
  *
  * `notEvaluated` is separate from a failed check on purpose. A threshold whose measurement never
  * recorded a sample has not passed -- it was not tested - and folding that into `passed` in
  * either direction is a lie: `true` claims a criterion was met that nobody measured, `false`
  * fails a campaign for a step the plan never scheduled.
  */
case class CampaignReport(
    campaign: String,
    drivers: List[String],
    latency: List[LatencySummary],
    taxonomy: ErrorTaxonomy,
    health: CampaignHealth,
    checks: List[ReportCheck],
    notEvaluated: List[String],
    passed: Boolean,
) derives JsonCodec

object CampaignReport:

  def assemble(
      campaign: String,
      reports: List[DriverHistogramReport],
      taxonomy: ErrorTaxonomy,
      health: CampaignHealth,
      thresholds: AcceptanceThresholds,
  ): Either[String, CampaignReport] =
    for
      _ <- reports.find(_.campaign != campaign) match
        case Some(foreign) =>
          Left(s"report for campaign '${foreign.campaign}' handed to the '$campaign' merge (driver ${foreign.driverId})")
        case None => Right(())
      samples <- reports.foldLeft[Either[String, Chunk[HistogramSample]]](Right(Chunk.empty)):
        case (Left(error), _)          => Left(error)
        case (Right(accumulated), one) => HistogramWire.decodeReport(one).map(accumulated ++ _)
    yield
      val merged = HistogramWire.merge(samples)
      val (checks, notEvaluated) = evaluate(merged, taxonomy, health, thresholds)
      CampaignReport(
        campaign = campaign,
        drivers = reports.map(_.driverId).distinct.sorted,
        latency = HistogramWire.summarise(merged).sortBy(_.id.toString),
        taxonomy = taxonomy,
        health = health,
        checks = checks,
        notEvaluated = notEvaluated,
        passed = checks.forall(_.passed) && notEvaluated.isEmpty,
      )

  private def evaluate(
      merged: Map[MeasurementId, org.HdrHistogram.Histogram],
      taxonomy: ErrorTaxonomy,
      health: CampaignHealth,
      thresholds: AcceptanceThresholds,
  ): (List[ReportCheck], List[String]) =
    val absolute = thresholds.latency.map: threshold =>
      measured(merged, threshold.id) match
        case None => Right(s"p99 of ${threshold.id}")
        case Some(histogram) =>
          val measured = histogram.getValueAtPercentile(99.0)
          val limit = HistogramWire.micros(threshold.p99)
          Left(
            ReportCheck(
              name = s"p99 of ${threshold.id}",
              passed = measured <= limit,
              detail = s"${measured}µs against a ${limit}µs ceiling",
            ),
          )

    val relative = thresholds.relativeLatency.map: threshold =>
      (measured(merged, threshold.id), measured(merged, threshold.relativeTo)) match
        case (Some(subject), Some(baseline)) =>
          val measured = subject.getValueAtPercentile(99.0)
          val limit = baseline.getValueAtPercentile(99.0) + HistogramWire.micros(threshold.margin)
          Left(
            ReportCheck(
              name = s"p99 of ${threshold.id} within ${threshold.margin.toMillis}ms of ${threshold.relativeTo}",
              passed = measured <= limit,
              detail = s"${measured}µs against a ${limit}µs ceiling",
            ),
          )
        case _ => Right(s"p99 of ${threshold.id} relative to ${threshold.relativeTo}")

    val health1 = health.scheduleLagP99 match
      case None => Right("schedule lag p99")
      case Some(lag) =>
        Left(
          ReportCheck(
            name = "schedule lag p99",
            passed = lag.toNanos <= thresholds.scheduleLagP99.toNanos,
            detail = s"${lag.toMillis}ms against a ${thresholds.scheduleLagP99.toMillis}ms ceiling",
          ),
        )

    val health2 = health.maxDriverCpu match
      case None => Right("driver CPU")
      case Some(cpu) =>
        Left(
          ReportCheck(
            name = "driver CPU",
            passed = cpu <= thresholds.maxDriverCpu,
            detail = f"${cpu * 100}%.1f%% against a ${thresholds.maxDriverCpu * 100}%.1f%% ceiling",
          ),
        )

    val counters = List(
      ReportCheck(
        name = "refresh rejections",
        passed = health.refreshRejectedTotal <= thresholds.maxRefreshRejected,
        detail = s"${health.refreshRejectedTotal} against a limit of ${thresholds.maxRefreshRejected}",
      ),
      ReportCheck(
        name = "dropped write-behind rows",
        passed = health.flushDroppedTotal <= thresholds.maxFlushDropped,
        detail = s"${health.flushDroppedTotal} against a limit of ${thresholds.maxFlushDropped}",
      ),
      ReportCheck(
        name = "clamped latencies",
        passed = health.latencyClampedTotal <= thresholds.maxLatencyClamped,
        detail = s"${health.latencyClampedTotal} against a limit of ${thresholds.maxLatencyClamped}",
      ),
      ReportCheck(
        name = "error budget",
        passed = taxonomy.budgetRatio <= thresholds.maxErrorBudgetRatio,
        detail =
          f"${taxonomy.budgetConsumed} of ${taxonomy.total} steps (${taxonomy.budgetRatio * 100}%.4f%%) " +
            f"against a ${thresholds.maxErrorBudgetRatio * 100}%.4f%% ceiling",
      ),
    )

    val outcomes = absolute ++ relative ++ List(health1, health2)
    (outcomes.collect { case Left(check) => check } ++ counters, outcomes.collect { case Right(missing) => missing })

  /** A measurement is only evaluable if something was actually recorded into it.
    *
    * HdrHistogram answers every percentile query on an empty histogram with 0, so a threshold
    * checked against one passes on a measured p99 of 0 rather than landing in `notEvaluated`. The
    * map having a key for the measurement is not enough: a driver that registered a recorder and
    * never recorded a sample -- a scenario that was configured but never ran, a step every attempt
    * failed before reaching -- reports a present, empty histogram. A campaign that measured
    * nothing at all would then report `passed = true`, which is the one answer the report must
    * never give by default.
    */
  private def measured(
      merged: Map[MeasurementId, org.HdrHistogram.Histogram],
      id: MeasurementId,
  ): Option[org.HdrHistogram.Histogram] =
    merged.get(id).filter(_.getTotalCount > 0L)

package versola.loadgen.metrics

import versola.loadgen.sut.{PoolerQueuePeak, PoolerStatsDelta, SutStatsDelta}
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

  /** §6.7's figures that are about the emulator rather than about one measurement: they judge
    * whether the instrument was trustworthy, so unlike a per-endpoint latency ceiling they are
    * the same for every campaign and are read from here rather than restated in config.
    */
  val scheduleLagP99: Duration = Duration.fromMillis(250)
  val maxDriverCpu: Double = 0.4

  /** "edge proxy p99 ≤ backend p99 + 15 ms". Fixed for the same reason, and separate from the
    * absolute ceilings because the campaign states which two measurements it is about but not
    * how much overhead an edge hop is allowed to add.
    */
  val edgeProxyMargin: Duration = Duration.fromMillis(15)

  /** The figures the design doc fixes, around the per-measurement ceilings a campaign states.
    *
    * The latency list is a parameter rather than a constant because both halves of it belong to
    * the campaign: the scenario/step naming belongs to the scenario engine, and the ceiling
    * belongs to the endpoint -- §6's table sets a different one per endpoint, and a single
    * hard-coded 120 ms would either fail every endpoint slower than `/token` or evaluate none of
    * them.
    */
  def designDefaults(
      latency: List[LatencyThreshold],
      edgeProxy: MeasurementId,
      mockBackend: MeasurementId,
  ): AcceptanceThresholds =
    AcceptanceThresholds(
      latency = latency,
      relativeLatency = List(RelativeLatencyThreshold(edgeProxy, mockBackend, edgeProxyMargin)),
      scheduleLagP99 = scheduleLagP99,
      maxRefreshRejected = 0L,
      maxFlushDropped = 0L,
      maxDriverCpu = maxDriverCpu,
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
    /** Microseconds, not a `zio.Duration`, because this one is read off a rendered report rather
      * than by another Scala process: a `Duration` encodes as an ISO-8601 string, which every
      * consumer would then have to parse before it could be compared against the threshold
      * printed next to it. The driver measures it in microseconds
      * ([[versola.loadgen.coordinator.DriverVitals.scheduleLagP99Micros]]) and it now stays that
      * way end to end.
      */
    scheduleLagP99Micros: Option[Long],
    latencyClampedTotal: Long,
) derives JsonCodec

/** One line of the verdict. `detail` carries the measured value so a failed check is actionable
  * without going back to the raw histograms.
  */
case class ReportCheck(name: String, passed: Boolean, detail: String) derives JsonCodec

/** One phase as the campaign was planned to run it, which is the one part of the header that is
  * an intention rather than a measurement -- the schedule is what the coordinator published, and
  * a phase's actual boundaries are only recoverable from the snapshot timeline.
  *
  * `scale` is present for a flat phase and absent for a ramp, where `scaleFrom`/`scaleTo` are; the
  * shape mirrors `CampaignPhaseConfig` rather than flattening it, because collapsing a ramp to
  * one number is what makes a report claim a steady rate the campaign never held.
  */
case class RunPhase(
    name: String,
    durationMillis: Long,
    scale: Option[Double],
    scaleFrom: Option[Double],
    scaleTo: Option[Double],
) derives JsonCodec

/** The distinct `expires_in` values one client was issued over the run. */
case class ObservedAccessTokenTtl(clientId: String, expiresInSeconds: List[Long]) derives JsonCodec

/** How the driver presented its access tokens.
  *
  * A choice and not a discovery: auth accepts a proof on any request and falls back to bearer
  * without one, so which mode a campaign ran in is decided by whether the driver was configured
  * to prove possession (`dpop` in [[versola.loadgen.config.LoadgenConfig]]), not by anything the
  * SUT was set to.
  *
  * That is exactly why the check below is worth making. The two facts -- what the driver sent and
  * what the SUT answered with -- are produced independently, so their agreement is evidence.
  */
enum TokenMode derives JsonCodec:
  case Bearer
  case Dpop

/** The run's own parameters, as `05-report-spec.md` §0 asks for them: what was driven, at what
  * scale, against what the SUT actually answered.
  *
  * Everything here that can be measured is measured. `population` is the store's own count rather
  * than `population.target` from config, and `shardCount` is the map that was in force rather
  * than the one the campaign was started with -- a rebalanced campaign that reported its
  * configured shard count would be describing a run that did not happen.
  */
case class CampaignRun(
    phases: List[RunPhase],
    population: Map[String, Long],
    shardCount: Int,
    shardEpoch: Long,
    tokenMode: TokenMode,
    observedTokenTypes: List[String],
    accessTokenTtls: List[ObservedAccessTokenTtl],
) derives JsonCodec

/** The body of `GET /report/{campaign}` (§12): merged quantiles, the error taxonomy, and the
  * verdict.
  *
  * `startEpochMillis`/`endEpochMillis` are the oldest and newest snapshot the merge actually saw,
  * not this coordinator's own uptime -- see `assemble`'s comment. Without them a dashboard has no
  * way to scope itself to one run's data rather than showing whatever the query range happens to
  * catch.
  *
  * `notEvaluated` is separate from a failed check on purpose. A threshold whose measurement never
  * recorded a sample has not passed -- it was not tested - and folding that into `passed` in
  * either direction is a lie: `true` claims a criterion was met that nobody measured, `false`
  * fails a campaign for a step the plan never scheduled.
  */
case class CampaignReport(
    campaign: String,
    drivers: List[String],
    startEpochMillis: Long,
    endEpochMillis: Long,
    run: CampaignRun,
    latency: List[LatencySummary],
    taxonomy: ErrorTaxonomy,
    health: CampaignHealth,
    checks: List[ReportCheck],
    notEvaluated: List[String],
    passed: Boolean,
    /** What the run cost each of the SUT's databases (runbook 05-report-spec.md §3): the
      * difference between the `pg_stat_*` snapshots bracketing the campaign. `None` for a
      * coordinator configured without SUT database credentials, and for one configured with them
      * whose campaign has not been stopped yet -- a delta needs both ends.
      *
      * Outside `checks` deliberately. Nothing here is a threshold: §3 is the sizing evidence the
      * report exists to produce, and a WAL rate has no pass mark to fail against.
      */
    databases: Option[List[SutStatsDelta]],
    /** What the run cost each PgBouncer in front of those databases (runbook 05-report-spec.md
      * §4): the difference between the admin console readings bracketing the campaign. `None` on
      * [[databases]]'s conditions, and independently of it -- a stack with a pooler and no SUT
      * credentials, or the reverse, is a configuration and not a mistake.
      *
      * Only the cumulative half of §4. The peak of the queue and the wait quantile need a time
      * series, which a bracket is not; see [[versola.loadgen.sut.PoolerPoolStats.maxWaitMicros]].
      */
    poolers: Option[List[PoolerStatsDelta]],
    /** The rest of §4: what the queue in front of each database reached over the run, sampled off
      * the same admin consoles on a timer because [[poolers]] cannot answer it.
      *
      * Independent of [[poolers]] in both directions. A campaign that was started but never
      * stopped has samples and no delta, which is the report an operator asks for *during* a run;
      * a coordinator that took over after the start has a delta it can compute from the table and
      * no samples from before its own boot. Both are the section the data supports rather than a
      * section withheld because its other half is missing.
      *
      * Outside `checks` for [[databases]]'s reason, and with one more: a sampled peak is a lower
      * bound (see [[versola.loadgen.sut.PoolerQueuePeak]]), and a criterion that can only ever
      * fail a run and never clear one is not a threshold.
      */
    poolerQueue: Option[List[PoolerQueuePeak]],
) derives JsonCodec

object CampaignReport:

  def assemble(
      campaign: String,
      reports: List[DriverHistogramReport],
      taxonomy: ErrorTaxonomy,
      health: CampaignHealth,
      run: CampaignRun,
      thresholds: AcceptanceThresholds,
      databases: Option[List[SutStatsDelta]],
      poolers: Option[List[PoolerStatsDelta]],
      poolerQueue: Option[List[PoolerQueuePeak]],
  ): Either[String, CampaignReport] =
    for
      _ <- Either.cond(reports.nonEmpty, (), s"no driver reports to build a time window from for campaign '$campaign'")
      _ <- reports.find(_.campaign != campaign) match
        case Some(foreign) =>
          Left(s"report for campaign '${foreign.campaign}' handed to the '$campaign' merge (driver ${foreign.driverId})")
        case None => Right(())
      samples <- reports.foldLeft[Either[String, Chunk[HistogramSample]]](Right(Chunk.empty)):
        case (Left(error), _) => Left(error)
        case (Right(accumulated), one) => HistogramWire.decodeReport(one).map(accumulated ++ _)
    yield
      val merged = HistogramWire.merge(samples)
      val (checks, notEvaluated) = evaluate(merged, taxonomy, health, run, thresholds)
      // The interval each driver actually wrote a snapshot in, not this coordinator's own
      // uptime: a restarted coordinator has no memory of the campaign's start, but every row
      // it just merged carries the instant its driver captured it (see `report`'s comment on
      // `Instant.EPOCH`), so the run's window is exactly what the data already says it is.
      CampaignReport(
        campaign = campaign,
        drivers = reports.map(_.driverId).distinct.sorted,
        startEpochMillis = reports.map(_.capturedAtEpochMillis).min,
        endEpochMillis = reports.map(_.capturedAtEpochMillis).max,
        run = run,
        latency = HistogramWire.summarise(merged).sortBy(_.id.toString),
        taxonomy = taxonomy,
        health = health,
        checks = checks,
        notEvaluated = notEvaluated,
        passed = checks.forall(_.passed) && notEvaluated.isEmpty,
        databases = databases,
        poolers = poolers,
        poolerQueue = poolerQueue,
      )

  private def evaluate(
      merged: Map[MeasurementId, org.HdrHistogram.Histogram],
      taxonomy: ErrorTaxonomy,
      health: CampaignHealth,
      run: CampaignRun,
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

    val health1 = health.scheduleLagP99Micros match
      case None => Right("schedule lag p99")
      case Some(lagMicros) =>
        Left(
          ReportCheck(
            name = "schedule lag p99",
            passed = lagMicros * 1000L <= thresholds.scheduleLagP99.toNanos,
            detail = s"${lagMicros / 1000L}ms against a ${thresholds.scheduleLagP99.toMillis}ms ceiling",
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

    // The run states the mode it drove in; the SUT states the mode it answered in. Neither is
    // evidence on its own -- a driver that sends no proof and a server that issues bearer tokens
    // agree, and that agreement is the claim -- so what is checked is that they did not diverge.
    // A campaign whose tokens came back sender-constrained while the driver presented them as
    // bearer measured a flow nobody asked for.
    // The values RFC 6749 §7.1 and RFC 9449 §5 give `token_type`, compared case-insensitively
    // below because neither RFC makes the casing significant and auth spells them "Bearer"/"DPoP".
    val expectedTokenType = run.tokenMode match
      case TokenMode.Bearer => "bearer"
      case TokenMode.Dpop => "dpop"
    val unexpectedTypes = run.observedTokenTypes.filterNot(_.equalsIgnoreCase(expectedTokenType))
    val tokenModeCheck =
      if run.observedTokenTypes.isEmpty then Right("token mode")
      else
        Left(
          ReportCheck(
            name = "token mode",
            passed = unexpectedTypes.isEmpty,
            detail =
              if unexpectedTypes.isEmpty then s"every token came back as $expectedTokenType"
              else s"${unexpectedTypes.sorted.mkString(", ")} against a run driven as $expectedTokenType",
          ),
        )

    val outcomes = absolute ++ relative ++ List(health1, health2, tokenModeCheck)
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

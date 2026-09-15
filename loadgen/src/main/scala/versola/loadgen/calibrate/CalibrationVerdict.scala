package versola.loadgen.calibrate

import versola.loadgen.config.CalibrationTargetsConfig
import versola.loadgen.metrics.{AcceptanceThresholds, LatencySummary, MeasurementId, ReportCheck}
import zio.Duration
import zio.json.JsonCodec

/** The result of the calibration gate (versolauth/versola#281): whether the driver reproduced
  * `mockapi`'s configured delay distribution, and by how much it missed.
  *
  * `checks`/`notEvaluated`/`passed` are [[versola.loadgen.metrics.CampaignReport]]'s shape and
  * rule, deliberately: a criterion whose measurement recorded nothing has not passed, it was not
  * tested, and folding that into `passed` in either direction is a lie. A calibration that
  * measured nothing must not report a pass -- that is the exact silent failure the gate exists
  * to make loud.
  */
case class CalibrationVerdict(
    campaign: String,
    latency: List[LatencySummary],
    checks: List[ReportCheck],
    notEvaluated: List[String],
    passed: Boolean,
) derives JsonCodec:

  /** The verdict as the run's log lines, which is where an operator reads it. */
  def lines: List[String] =
    s"Calibration verdict for '$campaign': ${if passed then "PASS" else "FAIL"}" ::
      checks.map(check => s"  [${if check.passed then "pass" else "FAIL"}] ${check.name}: ${check.detail}") ++
      notEvaluated.map(name => s"  [not evaluated] $name")

object CalibrationVerdict:

  /** #281's gate: "the driver's measured p50/p99 must match the sampler's *configured* p50/p99
    * within 2 ms".
    *
    * Not configurable, for the same reason [[AcceptanceThresholds.designDefaults]]' figures are
    * not: it is the issue's number, and a gate whose tolerance a campaign could widen is a gate
    * that reports a pass for a driver nobody calibrated. `CalibrationTargetsConfig` carries what
    * varies -- which distribution the backend was configured with -- and nothing else.
    */
  val tolerance: Duration = Duration.fromMillis(2L)

  /** #281's third acceptance line, "`schedule_lag` p99 < 250 ms at the calibration rate". The same
    * figure `AcceptanceThresholds.designDefaults` states, read from there rather than repeated, so
    * the gate and the campaign cannot come to disagree about when a driver is the bottleneck.
    */
  val scheduleLagP99: Duration = AcceptanceThresholds.designDefaults(
    tokenRefresh = MeasurementId.Flow("unused"),
    edgeProxy = MeasurementId.Flow("unused"),
    mockBackend = MeasurementId.Flow("unused"),
  ).scheduleLagP99

  /** How many samples a profile needs before its p99 is compared against a 2 ms tolerance.
    *
    * Below this the comparison is dominated by sampling error rather than by the instrument: the
    * p99 of ten thousand draws is decided by its top hundred, and the backend's own tail is the
    * 2%/10% core-banking branch, so a run that fell short of this has not tested the tail it
    * claims to have tested. Reported as *not evaluated* rather than as a pass, which is the whole
    * point of that list.
    */
  val minimumSamples: Long = 10_000L

  /** @param summaries the merged, decoded quantiles of the whole run -- `SnapshotMerge.summaries`
    *                  over the rows this run wrote, i.e. the same path `GET /report/{campaign}`
    *                  takes. Comparing against the driver's in-memory recorders instead would
    *                  leave the encode/decode/merge the campaign's verdict rests on untested,
    *                  which is one of the failure modes #281 names.
    */
  def assemble(
      campaign: String,
      summaries: List[LatencySummary],
      read: CalibrationTargetsConfig,
      write: CalibrationTargetsConfig,
      outcomes: CalibrationOutcomes,
      minimumSamples: Long,
  ): CalibrationVerdict =
    val byId = summaries.map(summary => summary.id -> summary).toMap

    val profiles = List(CalibrationProfile.Read -> read, CalibrationProfile.Write -> write)
      .map: (profile, targets) =>
        byId.get(profile.measurement).filter(_.count > 0L) match
          case None => Right(List(s"p50 and p99 of ${profile.label}"))
          case Some(summary) if summary.count < minimumSamples =>
            Right(List(f"p50 and p99 of ${profile.label} (only ${summary.count} samples, $minimumSamples needed)"))
          case Some(summary) =>
            Left(
              List(
                quantile(profile, "p50", summary.p50Micros, targets.p50),
                quantile(profile, "p99", summary.p99Micros, targets.p99),
              ),
            )

    val lag = byId.get(CalibrationLoop.scheduleLagMeasurement).filter(_.count > 0L) match
      case None => Right("schedule lag p99")
      case Some(summary) =>
        val limit = micros(scheduleLagP99)
        Left(
          ReportCheck(
            name = "schedule lag p99",
            passed = summary.p99Micros <= limit,
            detail = s"${summary.p99Micros}µs against a ${limit}µs ceiling",
          ),
        )

    // A failed call is not a slow one: it recorded no latency, so the quantiles above describe
    // only the calls that succeeded. One failure means the gate's own sample is incomplete.
    val failures = ReportCheck(
      name = "failed calls",
      passed = outcomes.failed == 0L,
      detail = s"${outcomes.failed} of ${outcomes.started} scheduled calls did not complete",
    )

    // The one check that is about the handoff rather than about the backend: every successful
    // call recorded exactly one sample, so the merged histograms must hold exactly as many as the
    // loop completed. A lost snapshot interval, a re-counted one, or a merge that re-bucketed
    // into the wrong geometry all show up here -- and none of them shows up in the quantiles,
    // which stay perfectly plausible while describing a subset of the run.
    val merged = CalibrationProfile.values.toList
      .flatMap(profile => byId.get(profile.measurement))
      .map(_.count)
      .sum
    val accounted = ReportCheck(
      name = "merged samples account for every completed call",
      passed = merged == outcomes.completed,
      detail = s"$merged samples merged against ${outcomes.completed} completed calls",
    )

    val checks = profiles.collect { case Left(pair) => pair }.flatten ++
      lag.left.toOption.toList ++ List(failures, accounted)
    val notEvaluated = profiles.collect { case Right(missing) => missing }.flatten ++ lag.toOption.toList

    CalibrationVerdict(
      campaign = campaign,
      latency = summaries.sortBy(_.id.toString),
      checks = checks,
      notEvaluated = notEvaluated,
      passed = checks.forall(_.passed) && notEvaluated.isEmpty,
    )

  /** One quantile against its configured value. The delta is signed and in the detail line
    * because that is what makes a failure actionable: a driver measuring *above* the backend is
    * overhead or queueing, while one measuring *below* it cannot be measuring the backend at all.
    */
  private def quantile(
      profile: CalibrationProfile,
      name: String,
      measuredMicros: Long,
      configured: Duration,
  ): ReportCheck =
    val configuredMicros = micros(configured)
    val delta = measuredMicros - configuredMicros
    ReportCheck(
      name = s"$name of ${profile.label} within ${tolerance.toMillis}ms of configured",
      passed = math.abs(delta) <= micros(tolerance),
      detail =
        f"measured ${measuredMicros / 1000.0}%.3f ms against a configured ${configuredMicros / 1000.0}%.3f ms " +
          f"(delta ${delta / 1000.0}%+.3f ms, tolerance ±${tolerance.toMillis}ms)",
    )

  private def micros(duration: Duration): Long = duration.toNanos / 1000L

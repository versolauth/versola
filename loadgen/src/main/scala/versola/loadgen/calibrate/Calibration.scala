package versola.loadgen.calibrate

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.loadgen.config.{CalibrationConfig, CampaignConfig, LoadgenConfig}
import versola.loadgen.coordinator.SnapshotMerge
import versola.loadgen.metrics.{HistogramSample, HistogramWire, LatencyRecorder, LatencySummary, MeasurementId}
import versola.loadgen.protocol.{AccessToken, ActionClient, EdgeActionClient, EdgeCredential, LoadgenHttpClient}
import versola.loadgen.scenario.BusinessActions
import versola.loadgen.scheduler.{ArrivalProcess, CampaignSchedule, RandomSource, ScheduleLag}
import versola.loadgen.store.{
  LoadgenMigrations,
  MeasurementKind,
  MetricSnapshotRepository,
  MetricSnapshotRow,
  PostgresMetricSnapshotRepository,
}
import versola.util.postgres.PostgresHikariDataSource
import zio.*
import zio.http.Client

import java.time.Instant

/** `role = calibrate`: the instrument's zero point (versolauth/versola#281).
  *
  * A fixed-rate run against `mockapi` and nothing else -- no auth, no edge, no central -- after
  * which the driver's own merged quantiles are compared against the distribution `mockapi` was
  * configured with. If the driver cannot reproduce a known distribution then none of its
  * measurements of the SUT mean anything, and the failure is silent: every request still returns
  * 200 and every number still looks like a number. So this exits non-zero on a miss, which is the
  * only form of "gate" a CI step or an operator can act on.
  *
  * What it deliberately reuses, because the reuse *is* the test: [[ArrivalProcess]]' thinning and
  * its intended-start recurrence, [[LatencyRecorder]]'s HdrHistograms, [[HistogramWire]]'s
  * encoding, `vu_metric_snapshots`, and [[SnapshotMerge]]. The merged histogram the verdict reads
  * has been through exactly the path a campaign's p99 goes through -- encode, base64url,
  * Postgres, decode, geometry check, add -- so a bug anywhere along it fails the gate here rather
  * than quietly moving a campaign's tail.
  *
  * What it does not do is publish a `CampaignReport`. That verdict is stated against the SUT's
  * own criteria (auth `/token` p99, edge proxy against backend), none of which a run with the SUT
  * out of the picture measures, so it would consist almost entirely of `notEvaluated`. The
  * calibration's criteria are its own; the snapshots it writes stay in the campaign's table, so a
  * later report and this verdict can be traced to the same rows.
  */
object Calibration:

  /** §11's snapshot interval. Kept at the campaign's value rather than shortened for a 30-minute
    * run: the handoff being on its production cadence is part of what is being calibrated, since
    * consecutive snapshots have to *partition* the recorded values for the merge to be a merge.
    */
  val snapshotInterval: Duration = 60.seconds

  /** The `driver_id` the gate's snapshots are written under. One process, so one id; distinct
    * from any driver's so a calibration's rows are never mistaken for a campaign's.
    */
  val driverId: String = "calibration"

  /** The bearer token every call presents. `mockapi` does not look at it -- it is behind edge in a
    * campaign, and edge is out of the picture here -- but the request goes out through the same
    * [[EdgeActionClient]] a campaign uses, and that client authenticates every request.
    */
  private val bearer: EdgeCredential = EdgeCredential.Bearer(AccessToken("calibration"))

  def calibrate(config: LoadgenConfig): ZIO[Scope & ConfigProvider, Throwable, Unit] =
    run(config).provideSome[Scope & ConfigProvider](LoadgenHttpClient.live)

  private def run(config: LoadgenConfig): ZIO[Scope & ConfigProvider & Client, Throwable, Unit] =
    for
      calibration <- ZIO.fromOption(config.calibration).orElseFail(MissingCalibrationConfig)
      _ <- ZIO.fromEither(fixedRate(config.campaign)).mapError(InvalidCalibration(_))
      actions <- ZIO.fromEither(BusinessActions.from(config.actions)).mapError(InvalidCalibration(_))
      _ <- ZIO.fromEither(bothProfiles(config)).mapError(InvalidCalibration(_))
      snapshots <- store
      latencies <- LatencyRecorder.make
      lag <- ScheduleLag.make
      outcomes <- Ref.make(CalibrationOutcomes.empty)
      client <- ZIO.service[Client]
      actionClient <- EdgeActionClient
        .at(client, config.targets.mockUrl, LoadgenHttpClient.requestTimeout)
        .mapError(error => InvalidCalibration(error.toString))
      startedAt <- Clock.instant
      schedule <- ZIO.fromEither(CampaignSchedule.from(config.campaign, startedAt)).mapError(InvalidCalibration(_))
      random = RandomSource.seeded(calibration.seed)
      loop = CalibrationLoop(
        arrivals = ArrivalProcess.startingAt(startedAt, random.split()),
        rate = schedule.envelope(calibration.ratePerSecond),
        client = actionClient,
        bearer = bearer,
        actions = actions,
        latencies = latencies,
        lag = lag,
        outcomes = outcomes,
        random = random,
        queueCapacity = CalibrationLoop.queueCapacity,
      )
      _ <- ZIO.logInfo(
        f"Calibrating against ${config.targets.mockUrl} at ${calibration.ratePerSecond}%.1f arrivals/s " +
          s"for ${schedule.totalDuration.render} (seed ${calibration.seed})",
      )
      summaries <- measure(config.campaign.name, loop, outcomes, latencies, snapshots, snapshotInterval, startedAt)
      settled <- outcomes.get
      verdict = CalibrationVerdict.assemble(
        campaign = config.campaign.name,
        summaries = summaries,
        read = calibration.read,
        write = calibration.write,
        outcomes = settled,
        minimumSamples = CalibrationVerdict.minimumSamples,
      )
      _ <- ZIO.foreachDiscard(verdict.lines)(ZIO.logInfo(_))
      _ <- ZIO.fail(CalibrationFailed(verdict)).unless(verdict.passed)
    yield ()

  /** The measurement half of the gate: run the loop to its horizon, snapshot on §11's cadence,
    * and read the whole run back out of the store as merged quantiles.
    *
    * Separate from the verdict, and from the config and store wiring, so that the path the
    * verdict is computed over -- arrival process, recorder, encode, store, decode, merge -- can
    * be driven end to end by a test with a backend whose distribution the test itself fixed. A
    * gate whose own plumbing is only ever exercised by the 30-minute run it gates is a gate that
    * gets debugged in 30-minute increments.
    *
    * Read back from `since = startedAt` rather than from `Instant.EPOCH` as the coordinator's
    * report does, and for the opposite reason: the coordinator merges a campaign that spans
    * restarts of itself, whereas a gate is one process and one run, and folding a previous
    * calibration's rows into this one's quantiles would hide the run that failed behind the run
    * that passed.
    */
  private[calibrate] def measure(
      campaign: String,
      loop: CalibrationLoop,
      outcomes: Ref[CalibrationOutcomes],
      latencies: LatencyRecorder,
      snapshots: MetricSnapshotRepository,
      interval: Duration,
      startedAt: Instant,
  ): ZIO[Scope, Throwable, List[LatencySummary]] =
    for
      publisher <- publish(campaign, latencies, snapshots).repeat(Schedule.spaced(interval)).forkScoped
      _ <- loop.run
      _ <- drain(outcomes)
      _ <- publisher.interrupt
      // The interval the timer did not reach. Without it the run's last samples are in a recorder
      // nobody read, and the gate would be judged on a partial run -- worse, on one whose missing
      // part is always the same part.
      _ <- publish(campaign, latencies, snapshots)
      rows <- snapshots.loadCampaign(campaign, startedAt)
      summaries <- ZIO.fromEither(SnapshotMerge.summaries(rows)).mapError(IllegalStateException(_))
    yield summaries

  /** Waits for the calls already in the air when the horizon was reached.
    *
    * Bounded by the request timeout plus a margin rather than unbounded: every call either
    * answers or is failed by [[LoadgenHttpClient.requestTimeout]], so anything still outstanding
    * past that is a fiber that will not settle, and waiting forever would hang the gate instead
    * of failing it. This only logs rather than failing the run directly, to avoid racing the very
    * fiber it is waiting on -- [[CalibrationVerdict]]'s "every scheduled call settled" check reads
    * `outcomes` after this returns and fails the gate if anything is still unaccounted for, which
    * is the same information without the race.
    */
  private def drain(outcomes: Ref[CalibrationOutcomes]): UIO[Unit] =
    outcomes.get
      .flatMap(current => ZIO.sleep(drainPoll).when(current.inFlight > 0L).as(current.inFlight))
      .repeatUntil(_ <= 0L)
      .timeout(LoadgenHttpClient.requestTimeout.plus(drainPoll))
      .flatMap:
        case Some(_) => ZIO.unit
        case None =>
          outcomes.get.flatMap(current =>
            ZIO.logWarning(s"${current.inFlight} calibration calls were still in flight at the drain deadline"),
          )

  private val drainPoll: Duration = 100.millis

  /** One snapshot interval: take and reset every recorder, and write what actually holds samples.
    *
    * Empty histograms are skipped rather than written as zero-count rows. They would decode and
    * merge correctly, but a measurement that is present and empty is what
    * `CampaignReport.measured` exists to tell apart from one that recorded something, and filling
    * the table with rows that say nothing makes that distinction harder to see, not easier.
    */
  private[calibrate] def publish(
      campaign: String,
      latencies: LatencyRecorder,
      snapshots: MetricSnapshotRepository,
  ): Task[Unit] =
    for
      capturedAt <- Clock.instant
      samples <- latencies.snapshot
      rows = samples.filter(_.histogram.getTotalCount > 0L).map(rowOf(campaign, capturedAt, _))
      _ <- snapshots.appendAll(rows)
      _ <- ZIO.logDebug(s"Wrote ${rows.size} calibration snapshot rows at $capturedAt").when(rows.nonEmpty)
    yield ()

  /** Track F's label into the store's `(kind, scenario, name)` columns -- the inverse of
    * [[SnapshotMerge.measurementOf]], which is what reads them back.
    */
  private[calibrate] def rowOf(campaign: String, capturedAt: Instant, sample: HistogramSample): MetricSnapshotRow =
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

  /** #281 asks for "a 30-minute run at a known fixed rate". Every one of these is ordinary
    * campaign configuration, so this rejects only the shapes under which the rate is *not* fixed
    * -- and rejects them at boot, because a gate run at a rate nobody can state afterwards
    * produces quantiles that cannot be attributed to anything.
    */
  private[calibrate] def fixedRate(campaign: CampaignConfig): Either[String, Unit] =
    for
      _ <- Either.cond(
        !campaign.diurnal.enabled,
        (),
        "a calibration run requires campaign.diurnal.enabled = false; a diurnal envelope is not a fixed rate",
      )
      _ <- Either.cond(
        !campaign.registration.enabled,
        (),
        "a calibration run drives mockapi only, so campaign.registration.enabled must be false",
      )
      phase <- campaign.phases match
        case single :: Nil => Right(single)
        case phases => Left(s"a calibration run requires exactly one campaign phase, got ${phases.size}")
      _ <- Either.cond(
        phase.scale.isDefined,
        (),
        s"campaign phase '${phase.name}' must set a flat scale; a ramp is not a fixed rate",
      )
      _ <- Either.cond(
        phase.scale.exists(_ > 0.0),
        (),
        s"campaign phase '${phase.name}' must set a positive scale, got ${phase.scale.getOrElse(0.0)}",
      )
      _ <- Either.cond(
        phase.duration.toMillis > 0L,
        (),
        s"campaign phase '${phase.name}' must have a positive duration, got ${phase.duration}",
      )
    yield ()

  /** Both of `mockapi`'s profiles have to be exercised, and every configured action has to be
    * drawable.
    *
    * An `actions` list of reads alone leaves the write profile's quantiles unmeasured -- reported
    * as not evaluated, so the gate fails rather than passing on half a calibration, but the
    * message would point at the measurement instead of at the config. An action carrying an `acr`
    * is worse: `BusinessActions.pickOrdinary` never draws it, so it is silently absent from a mix
    * the operator believes they configured, and `mockapi` has no step-up path for it to reach.
    */
  private[calibrate] def bothProfiles(config: LoadgenConfig): Either[String, Unit] =
    val drawn = config.actions.flatMap: action =>
      zio.http.Method.fromString(action.method) match
        case zio.http.Method.CUSTOM(_) => None
        case known => Some(CalibrationProfile.of(known))
    for
      _ <- config.actions.find(_.acr.isDefined) match
        case Some(action) =>
          Left(s"action '${action.name}' requires an acr; a calibration run against mockapi has no step-up path")
        case None => Right(())
      missing = CalibrationProfile.values.toList.filterNot(drawn.contains)
      _ <- Either.cond(
        missing.isEmpty,
        (),
        s"actions must exercise both of mockapi's delay profiles; none of them is a ${missing.map(_.label).mkString(", ")}",
      )
    yield ()

  private def store: ZIO[Scope & ConfigProvider, Throwable, MetricSnapshotRepository] =
    PostgresHikariDataSource
      .transactor(
        serviceName = Some("loadgen-calibrate"),
        migrate = true,
        validateOnMigrate = true,
        migrationLocations = Some(LoadgenMigrations.locations),
        configPath = Seq("store", "postgres"),
      )
      .build
      .map(environment => PostgresMetricSnapshotRepository(environment.get[TransactorZIO]))

case object MissingCalibrationConfig
    extends RuntimeException("role = calibrate requires a 'calibration' configuration block")

case class InvalidCalibration(reason: String) extends RuntimeException(reason)

/** The gate refusing. Carries the verdict so the exit is readable without going back to the log.
  */
case class CalibrationFailed(verdict: CalibrationVerdict)
  extends RuntimeException(s"calibration gate failed: ${verdict.lines.mkString("; ")}")

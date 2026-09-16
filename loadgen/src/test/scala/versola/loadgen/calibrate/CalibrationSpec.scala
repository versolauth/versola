package versola.loadgen.calibrate

import versola.loadgen.config.*
import versola.loadgen.metrics.{LatencyRecorder, LatencySummary, MeasurementId}
import versola.loadgen.protocol.{
  AccessToken,
  ActionCall,
  ActionClient,
  EdgeActionClient,
  EdgeCredential,
  LoadgenHttpClient,
}
import versola.loadgen.scenario.BusinessActions
import versola.loadgen.scheduler.{ArrivalProcess, CampaignSchedule, RandomSource, ScheduleLag}
import versola.loadgen.store.{MetricSnapshotRepository, MetricSnapshotRow}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.test.*

import java.time.Instant

/** The calibration gate of versolauth/versola#281, tested at both ends.
  *
  * The verdict half is pinned on synthetic quantiles, because the whole value of the gate is the
  * *boundary*: 1.9 ms from the configured value has to pass and 2.1 ms has to fail, and a run
  * against a real backend can never be arranged to land exactly there.
  *
  * The measurement half is driven end to end against a backend whose delay this test fixes, so
  * the assertion is the gate's own: the merged p50 and p99, read back out of the snapshot store
  * through `SnapshotMerge`, must be within 2 ms of what the stub was told to sleep. That covers
  * the whole chain #281 names as the usual suspects -- the intended-start anchoring, the
  * HdrHistogram encode/decode, the snapshot partitioning and the merge -- without needing the
  * `mockapi` process, which `loadgen` deliberately does not depend on.
  */
object CalibrationSpec extends ZIOSpecDefault:

  private val readDelay: Duration = 6.millis
  private val writeDelay: Duration = 13.millis

  /** What the measurement test allows between the stub's configured delay and the driver's
    * reported quantile.
    *
    * Wider than the gate's 2 ms, and it has to be: the stub sleeps in the *same JVM* as the
    * driver, on `ClockLive`, whose wakeup is good to a millisecond or two and no better, and the
    * loopback request travels through a second netty stack competing for the same cores. All of
    * that is harness overhead the driver correctly reports as latency -- it is indistinguishable,
    * from inside the driver, from a backend that really was slower.
    *
    * So this test is not where the 2 ms lives. It pins the pipeline: a wrong time unit, a lost
    * snapshot interval, a latency measured from the wrong instant or a merge over the wrong
    * geometry all miss by far more than this. The 2 ms boundary is pinned exactly, on synthetic
    * quantiles, by the verdict suite; and against the real `mockapi` by the 30-minute run the
    * gate exists to be.
    */
  private val harnessTolerance: Duration = 20.millis

  private val readPath = "/resources/core/accounts"
  private val writePath = "/resources/pay/p2p"

  private val actionsConfig = List(
    BusinessActionConfig("accounts", 0.7, "GET", readPath, None),
    BusinessActionConfig("p2p", 0.3, "POST", writePath, None),
  )

  /** `mockapi`'s contract, reduced to what the gate measures: always 200, after a delay the
    * caller did not choose. Constant rather than sampled on purpose -- a stub that sampled a
    * distribution would make this test's own p99 a sampling question, and what is under test here
    * is the instrument, not the sampler.
    */
  private val stubRoutes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "resources" / "core" / "accounts" -> handler { (_: Request) =>
        Clock.ClockLive.sleep(readDelay).as(Response.json("""{"accounts":[]}"""))
      },
      Method.POST / "resources" / "pay" / "p2p" -> handler { (_: Request) =>
        Clock.ClockLive.sleep(writeDelay).as(Response.json("""{"status":"accepted"}"""))
      },
    )

  /** The store, without Postgres. `appendAll`'s idempotency is the repository's own contract and
    * is tested where it lives (`PostgresMetricSnapshotRepositorySpec`); what this needs is for the
    * rows to survive the round trip so the merge has something to merge.
    */
  private final class InMemorySnapshots(rows: Ref[Vector[MetricSnapshotRow]]) extends MetricSnapshotRepository:
    override def appendAll(snapshots: Chunk[MetricSnapshotRow]): Task[Unit] =
      rows.update(_ ++ snapshots)

    override def loadCampaign(campaign: String, since: Instant): Task[Vector[MetricSnapshotRow]] =
      rows.get.map(_.filter(row => row.campaign == campaign && !row.capturedAt.isBefore(since)).sortBy(_.capturedAt))

  private def summary(id: MeasurementId, count: Long, p50Micros: Long, p99Micros: Long): LatencySummary =
    LatencySummary(
      id = id,
      count = count,
      minMicros = p50Micros,
      maxMicros = p99Micros,
      meanMicros = p50Micros.toDouble,
      p50Micros = p50Micros,
      p90Micros = p50Micros,
      p95Micros = p50Micros,
      p99Micros = p99Micros,
      p999Micros = p99Micros,
    )

  /** A run that measured both profiles exactly as configured, plus a healthy schedule lag --
    * the baseline every verdict assertion below perturbs one field of.
    */
  private def perfect(samples: Long): List[LatencySummary] =
    List(
      summary(CalibrationProfile.Read.measurement, samples, 6000L, 46000L),
      summary(CalibrationProfile.Write.measurement, samples, 13500L, 50000L),
      summary(CalibrationLoop.scheduleLagMeasurement, samples * 2L, 100L, 1000L),
    )

  private val designRead = CalibrationTargetsConfig(6.millis, 46.millis)

  /** `mockapi`'s configured write p50 is 13.5 ms, not a whole number of milliseconds -- stated in
    * nanos so the verdict is compared against the real figure rather than a rounded one.
    */
  private val designWrite = CalibrationTargetsConfig(Duration.fromNanos(13_500_000L), 50.millis)

  private def verdictOf(
      summaries: List[LatencySummary],
      outcomes: CalibrationOutcomes,
      minimumSamples: Long = 1000L,
  ): CalibrationVerdict =
    CalibrationVerdict.assemble(
      campaign = "calibration",
      summaries = summaries,
      read = designRead,
      write = designWrite,
      outcomes = outcomes,
      minimumSamples = minimumSamples,
    )

  private def profile(summaries: List[LatencySummary], id: MeasurementId): Task[LatencySummary] =
    ZIO
      .fromOption(summaries.find(_.id == id))
      .orElseFail(IllegalStateException(s"the run recorded nothing under $id"))

  /** At or above the configured delay, and no further above it than the harness costs. */
  private def within(measuredMicros: Long, configured: Duration): Boolean =
    val configuredMicros = configured.toNanos / 1000L
    measuredMicros >= configuredMicros && measuredMicros - configuredMicros <= harnessTolerance.toNanos / 1000L

  private def settled(samples: Long): CalibrationOutcomes =
    CalibrationOutcomes(started = samples * 2L, completed = samples * 2L, failed = 0L)

  private def campaign(name: String, duration: Duration): CampaignConfig =
    CampaignConfig(
      name = name,
      phases = List(CampaignPhaseConfig("calibration", duration, Some(1.0), None, None)),
      diurnal = DiurnalConfig(enabled = false, peakFactor = 1.0, peakHour = 20, timezone = "UTC"),
      registration = RegistrationConfig(enabled = false, target = 0L, duration = 1.second),
    )

  /** The real client against the stub, over a real socket on an ephemeral port.
    *
    * `TestClient` would have been simpler and is the wrong tool here: it dispatches into the
    * routes in-process, so the netty client, the connection pool and the body read -- every part
    * of the driver that could add latency the gate would then attribute to the backend -- would
    * not be in the measurement at all.
    */
  private def stubServer: ZIO[Server & Client, Throwable, ActionClient] =
    for
      port <- Server.install(stubRoutes)
      client <- ZIO.service[Client]
      actions <- EdgeActionClient
        .at(client, s"http://localhost:$port", LoadgenHttpClient.requestTimeout)
        .mapError(error => IllegalStateException(error.toString))
    yield actions

  /** The stub and the driver's own client, for the whole of one test.
    *
    * Provided at the test rather than inside the effect that starts them: `provideSome` closes
    * the scope it built the layer in as soon as that effect returns, so a client acquired inside
    * `stubServer` would already have been shut down by the time the first arrival used it.
    */
  private val sut: ZLayer[Any, Throwable, Server & Client] =
    LoadgenHttpClient.live ++
      ((ZLayer.succeed(Server.Config.default.onAnyOpenPort) ++
        ZLayer.succeed(NettyConfig.default)) >>> Server.customized)

  private val bearer: EdgeCredential = EdgeCredential.Bearer(AccessToken("calibration"))

  /** JIT, class loading and the first connections of the pool all land on the earliest arrivals,
    * and the gate's p99 is decided by the worst one percent -- so a run this short has to be
    * warmed before it is measured. A 30-minute run at campaign rates does not need this; a
    * ten-second one would otherwise be measuring the JVM's startup.
    */
  private def warmUp(client: ActionClient, calls: Int): Task[Unit] =
    ZIO
      .foreachDiscard(1 to calls): index =>
        val call =
          if index % 2 == 0 then ActionCall(Method.GET, readPath, None)
          else ActionCall(Method.POST, writePath, Some("""{"reference":"warmup"}"""))
        client.call(bearer, call).unit
      .mapError(error => IllegalStateException(error.toString))

  private def measured(
      ratePerSecond: Double,
      duration: Duration,
  ): ZIO[Scope & Server & Client, Throwable, (List[LatencySummary], CalibrationOutcomes)] =
    for
      client <- stubServer
      _ <- warmUp(client, 200)
      actions <- ZIO.fromEither(BusinessActions.from(actionsConfig)).mapError(IllegalStateException(_))
      latencies <- LatencyRecorder.make
      lag <- ScheduleLag.make
      outcomes <- Ref.make(CalibrationOutcomes.empty)
      rows <- Ref.make(Vector.empty[MetricSnapshotRow])
      snapshots = InMemorySnapshots(rows)
      startedAt <- Clock.instant
      schedule <- ZIO.fromEither(CampaignSchedule.from(campaign("calibration", duration), startedAt))
        .mapError(IllegalStateException(_))
      random = RandomSource.seeded(20260915L)
      loop = CalibrationLoop(
        arrivals = ArrivalProcess.startingAt(startedAt, random.split()),
        rate = schedule.envelope(ratePerSecond),
        client = client,
        bearer = bearer,
        actions = actions,
        latencies = latencies,
        lag = lag,
        outcomes = outcomes,
        random = random,
        queueCapacity = CalibrationLoop.queueCapacity,
      )
      // Two snapshot intervals inside the run, so the merge is over a partition and not over one
      // interval -- a cumulative snapshot would double-count and is invisible in the quantiles.
      summaries <- Calibration.measure(
        campaign = "calibration",
        loop = loop,
        outcomes = outcomes,
        latencies = latencies,
        snapshots = snapshots,
        interval = duration.dividedBy(3L),
        startedAt = startedAt,
      )
      finished <- outcomes.get
    yield (summaries, finished)

  def spec = suite("Calibration")(
    suite("verdict")(
      test("passes when both profiles reproduce the configured quantiles") {
        val verdict = verdictOf(perfect(5000L), settled(5000L))
        assertTrue(verdict.passed, verdict.notEvaluated.isEmpty, verdict.checks.forall(_.passed))
      },
      // The boundary is the gate. A driver 1.9 ms above the backend is overhead the design
      // budgets for; one 2.1 ms above it is an instrument whose numbers are not the backend's.
      test("passes at 1.9 ms from configured and fails at 2.1 ms, for p50 and for p99") {
        val insideP50 = verdictOf(
          perfect(5000L).map {
            case s if s.id == CalibrationProfile.Read.measurement => s.copy(p50Micros = 7900L)
            case other => other
          },
          settled(5000L),
        )
        val outsideP50 = verdictOf(
          perfect(5000L).map {
            case s if s.id == CalibrationProfile.Read.measurement => s.copy(p50Micros = 8100L)
            case other => other
          },
          settled(5000L),
        )
        val insideP99 = verdictOf(
          perfect(5000L).map {
            case s if s.id == CalibrationProfile.Write.measurement => s.copy(p99Micros = 51900L)
            case other => other
          },
          settled(5000L),
        )
        val outsideP99 = verdictOf(
          perfect(5000L).map {
            case s if s.id == CalibrationProfile.Write.measurement => s.copy(p99Micros = 52100L)
            case other => other
          },
          settled(5000L),
        )
        assertTrue(insideP50.passed, !outsideP50.passed, insideP99.passed, !outsideP99.passed)
      },
      // Below the configured value, too: a driver that reports less than the backend was told to
      // sleep is not measuring the backend, and a one-sided check would call that a pass.
      test("fails when the measured quantile is below the configured one by more than the tolerance") {
        val verdict = verdictOf(
          perfect(5000L).map {
            case s if s.id == CalibrationProfile.Read.measurement => s.copy(p99Micros = 43000L)
            case other => other
          },
          settled(5000L),
        )
        assertTrue(!verdict.passed)
      },
      test("reports a profile that recorded nothing as not evaluated rather than as a pass") {
        val verdict = verdictOf(perfect(5000L).filterNot(_.id == CalibrationProfile.Write.measurement), settled(5000L))
        assertTrue(
          !verdict.passed,
          verdict.notEvaluated.exists(_.contains(CalibrationProfile.Write.label)),
        )
      },
      // A p99 over a few hundred draws is its own top handful of samples; comparing that against
      // a 2 ms tolerance measures the draw, not the driver.
      test("reports a profile with too few samples as not evaluated") {
        val verdict = verdictOf(perfect(500L), settled(500L), minimumSamples = 1000L)
        assertTrue(!verdict.passed, verdict.notEvaluated.size == 2)
      },
      test("fails when the schedule lag p99 breaches the 250 ms ceiling") {
        val verdict = verdictOf(
          perfect(5000L).map {
            case s if s.id == CalibrationLoop.scheduleLagMeasurement => s.copy(p99Micros = 250_001L)
            case other => other
          },
          settled(5000L),
        )
        assertTrue(
          !verdict.passed,
          verdict.checks.exists(check => check.name == "schedule lag p99" && !check.passed),
        )
      },
      test("fails on a single failed call, which is a sample the quantiles do not contain") {
        val verdict = verdictOf(perfect(5000L), settled(5000L).copy(started = 10_001L, failed = 1L))
        assertTrue(!verdict.passed, verdict.checks.exists(check => check.name == "failed calls" && !check.passed))
      },
      // Neither completed nor failed: `Calibration.drain` gave up at its deadline while this call
      // was still in the air, and only logged it. Without this check the call would fall out of
      // every other one -- the failed-calls check above would still read zero.
      test("fails when a call is still in flight at the drain deadline") {
        val verdict = verdictOf(perfect(5000L), settled(5000L).copy(started = 10_001L))
        assertTrue(
          !verdict.passed,
          verdict.checks.exists(check => check.name == "every scheduled call settled" && !check.passed),
        )
      },
      // The one check that catches a lost snapshot interval or a merge that re-bucketed: the
      // quantiles stay perfectly plausible while describing a subset of the run.
      test("fails when the merged sample count does not account for every completed call") {
        val verdict = verdictOf(perfect(5000L), settled(5000L).copy(completed = 12_000L))
        assertTrue(
          !verdict.passed,
          verdict.checks.exists(check => check.name.startsWith("merged samples") && !check.passed),
        )
      },
    ),
    suite("campaign shape")(
      test("accepts a single flat phase with no diurnal envelope") {
        assertTrue(Calibration.fixedRate(campaign("c", 30.minutes)).isRight)
      },
      test("rejects the shapes under which the rate is not fixed") {
        val flat = campaign("c", 30.minutes)
        val ramp = flat.copy(phases = List(CampaignPhaseConfig("ramp", 30.minutes, None, Some(0.1), Some(1.0))))
        val twoPhases = flat.copy(phases = flat.phases ++ flat.phases)
        val diurnal = flat.copy(diurnal = flat.diurnal.copy(enabled = true))
        val registering = flat.copy(registration = flat.registration.copy(enabled = true))
        val empty = flat.copy(phases = List(CampaignPhaseConfig("idle", Duration.Zero, Some(1.0), None, None)))
        assertTrue(
          Calibration.fixedRate(ramp).isLeft,
          Calibration.fixedRate(twoPhases).isLeft,
          Calibration.fixedRate(diurnal).isLeft,
          Calibration.fixedRate(registering).isLeft,
          Calibration.fixedRate(empty).isLeft,
        )
      },
    ),
    suite("measurement")(
      // The gate's own pipeline, end to end against a backend of known delay: the arrival
      // process, the intended-start anchoring, the recorder, the snapshot partition, the
      // encode/decode and the merge. Judged against the stub's configured delay, but on
      // `harnessTolerance` rather than on #281's 2 ms -- see that value for why, and see the
      // verdict suite above for where the 2 ms boundary itself is pinned.
      test("reproduces a known distribution end to end, through the store and the merge") {
        for
          result <- measured(ratePerSecond = 150.0, duration = 10.seconds)
          (summaries, outcomes) = result
          _ <- ZIO.foreachDiscard(summaries)(summary => Console.printLine(summary.toString)).orDie
          read <- profile(summaries, CalibrationProfile.Read.measurement)
          write <- profile(summaries, CalibrationProfile.Write.measurement)
          lag <- profile(summaries, CalibrationLoop.scheduleLagMeasurement)
        yield assertTrue(
          outcomes.failed == 0L,
          outcomes.inFlight == 0L,
          // No snapshot interval lost, none counted twice, nothing re-bucketed away.
          read.count + write.count == outcomes.completed,
          lag.count == outcomes.started,
          // Above the backend's delay by the harness's own overhead and no more. The lower bound
          // is the load-bearing half: a driver reporting *less* than the stub slept for would be
          // measuring from the wrong instant, which is the failure a passing p50 hides best.
          within(read.p50Micros, readDelay),
          within(read.p99Micros, readDelay),
          within(write.p50Micros, writeDelay),
          within(write.p99Micros, writeDelay),
          lag.p99Micros <= CalibrationVerdict.scheduleLagP99.toNanos / 1000L,
        )
      }.provideSome[Scope](sut),
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds),
  )

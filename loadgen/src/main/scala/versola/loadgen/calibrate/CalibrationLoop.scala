package versola.loadgen.calibrate

import versola.loadgen.metrics.{IntendedLatency, LatencyRecorder, LoadgenMetrics, MeasurementId}
import versola.loadgen.protocol.{ActionCall, ActionClient, EdgeCredential, ProtocolError}
import versola.loadgen.scenario.BusinessActions
import versola.loadgen.scheduler.*
import zio.*
import zio.http.Method

import java.time.Instant

/** Which of `mockapi`'s two delay profiles a call draws from, and the measurement the gate reads
  * it back under.
  *
  * Two measurements and not one: the read and write mixtures differ in their branch weights
  * (`versola.mockapi.MixtureWeights`), so they have different configured quantiles, and a single
  * histogram over both would be a mixture whose p50 depends on the action weights rather than on
  * anything the backend was configured to do. The gate compares each against its own target.
  */
enum CalibrationProfile(val label: String):
  case Read extends CalibrationProfile("mock-read")
  case Write extends CalibrationProfile("mock-write")

  /** A flow rather than a step: there is no scenario here, only one timed hop per arrival, which
    * is exactly the granularity §11 names on its own.
    */
  def measurement: MeasurementId = MeasurementId.Flow(label)

object CalibrationProfile:
  /** `mockapi`'s six read endpoints are precisely its `GET`s and its four write endpoints are
    * precisely the rest (`versola.mockapi.Endpoints`), so the profile is a property of the method
    * and needs no second list in the config file to stay in step with it.
    */
  def of(method: Method): CalibrationProfile =
    if method == Method.GET then Read else Write

/** How many calls the run started, how many came back, and how many came back as something other
  * than a success.
  *
  * Failures are counted rather than recorded as latencies. A transport error has no delay the
  * backend chose, so putting its elapsed time in the histogram would move the very quantiles the
  * gate is comparing -- and a run with failures is not a calibration of anything, which is why
  * the verdict fails on a non-zero count instead of tolerating a few.
  *
  * `started` is separate because the horizon does not wait for the calls already in the air. The
  * run has to drain to `inFlight == 0` before its last snapshot is taken, or the final interval
  * is missing exactly the calls that were slowest to answer -- a bias toward the flattering end
  * of the tail the gate is checking.
  */
final case class CalibrationOutcomes(started: Long, completed: Long, failed: Long):
  def settled: Long = completed + failed

  def inFlight: Long = started - settled

object CalibrationOutcomes:
  val empty: CalibrationOutcomes = CalibrationOutcomes(0L, 0L, 0L)

/** The calibration gate's execution loop: §7.2's arrival process on one side, one `mockapi` call
  * per arrival on the other (versolauth/versola#281).
  *
  * Shaped like [[versola.loadgen.scenario.DriverLoop]] -- a generator filling a bounded queue
  * ahead of the clock, a dispatcher sleeping to each arrival's intended start and forking it --
  * and reusing that loop's actual machinery where it is the thing under test: [[ArrivalProcess]]
  * and its thinning, [[ScheduleLag]], and the intended-start anchoring of every recorded latency.
  *
  * It is not that loop, for one reason that is not stylistic: a driver holds a virtual user for
  * the whole of a session ([[versola.loadgen.scenario.BusyUsers]]) and *skips* an arrival whose
  * user is already busy. A calibration run has no virtual users, so a pool of synthetic ones
  * would either be large enough to be pointless bookkeeping or small enough to silently drop
  * arrivals -- and an instrument that drops part of its own known input cannot be used to prove
  * the instrument reproduces a known input.
  *
  * Every latency is measured from `intendedStart`, never from the moment the fiber got to run.
  * That is the whole point of the gate: a driver that falls behind reports its own queueing delay
  * as latency, so a p50 that matches the backend's configured p50 is simultaneously evidence that
  * the backend was reproduced and that the driver was not the bottleneck.
  */
final class CalibrationLoop(
    arrivals: ArrivalProcess,
    rate: VaryingRate,
    client: ActionClient,
    bearer: EdgeCredential,
    actions: BusinessActions,
    latencies: LatencyRecorder,
    lag: ScheduleLag,
    outcomes: Ref[CalibrationOutcomes],
    random: RandomSource,
    queueCapacity: Int,
):

  /** Runs until the campaign's horizon is reached or the fiber is interrupted. */
  def run: ZIO[Scope, Throwable, Unit] =
    for
      queue <- Queue.bounded[Option[ScheduledArrival]](queueCapacity)
      // As in `DriverLoop`: the generator's death has to reach a dispatcher that is otherwise
      // parked on a queue nothing will fill again.
      generatorFailed <- Promise.make[Throwable, Nothing]
      generator <- generate(queue).sandbox.catchAll(generatorFailed.failCause).forkScoped
      _ <- dispatch(queue).raceFirst(generatorFailed.await).ensuring(generator.interrupt)
    yield ()

  /** The horizon travels through the queue as a `None` rather than as a `shutdown`, so the
    * dispatcher reaches it only after the arrivals already queued ahead of it -- a gate that
    * stopped short of its own schedule would be a gate run for an unknown length of time.
    */
  private def generate(queue: Queue[Option[ScheduledArrival]]): UIO[Unit] =
    ZIO
      .suspendSucceed(ZIO.succeed(arrivals.next(rate)))
      .flatMap:
        case Some(arrival) => queue.offer(Some(arrival)).as(true)
        case None => queue.offer(None).as(false)
      .repeatWhile(identity)
      .unit

  private def dispatch(queue: Queue[Option[ScheduledArrival]]): ZIO[Scope, Throwable, Unit] =
    queue.take.flatMap:
      case None => lag.observeHead(None)
      case Some(arrival) =>
        for
          _ <- lag.observeHead(Some(arrival.intendedStart))
          _ <- sleepUntil(arrival.intendedStart)
          _ <- start(arrival)
          depth <- queue.size
          _ <- lag.observeHead(None).when(depth <= 0)
          _ <- dispatch(queue)
        yield ()

  /** Forks the call and moves on, for the same reason `DriverLoop` does: awaiting it would turn
    * the open model into a closed loop of concurrency one, whose measured latency is then a
    * property of the loop rather than of the backend.
    */
  private def start(arrival: ScheduledArrival): ZIO[Scope, Nothing, Unit] =
    // The path parameters of the ten actions are substituted from an id; the arrival's sequence
    // number serves, since `mockapi` does not look at them and there is no population here.
    actions.call(actions.pickOrdinary(random), arrival.sequence) match
      case Left(detail) =>
        // Unreachable: `BusinessActions.from` has already rejected an unknown method. Reported
        // rather than ignored, because a gate that silently made fewer calls than it scheduled
        // would still produce quantiles.
        ZIO.logError(s"Calibration could not build a call: $detail") *>
          outcomes.update(current => failed(started(current))).unit
      case Right(call) =>
        LoadgenMetrics.arrival(CalibrationLoop.scenarioLabel) *>
          outcomes.update(started) *>
          perform(arrival, call).forkScoped.unit

  private def perform(arrival: ScheduledArrival, call: ActionCall): UIO[Unit] =
    for
      startedAt <- Clock.instant
      // How late the driver was in starting a unit it had already scheduled -- the emulator's own
      // contribution to every latency below it. Recorded into the same recorder as the latencies
      // so it lands in `vu_metric_snapshots` alongside them: the gate's schedule-lag criterion is
      // stated on a *quantile*, and a gauge cannot be read after the fact.
      _ <- latencies.record(
        CalibrationLoop.scheduleLagMeasurement,
        IntendedLatency.unsafe(IntendedStart.startDelay(arrival.intendedStart, startedAt)),
      )
      outcome <- client.call(bearer, call).either
      completedAt <- Clock.instant
      _ <- outcome match
        case Right(_) =>
          latencies.record(
            CalibrationProfile.of(call.method).measurement,
            IntendedLatency.between(arrival.intendedStart, completedAt),
          ) *> outcomes.update(completed).unit
        case Left(error) => report(call, error)
    yield ()

  private def report(call: ActionCall, error: ProtocolError): UIO[Unit] =
    outcomes.update(failed) *> ZIO.logWarning(s"Calibration call ${call.method} ${call.path} failed: $error")

  /** Sleeps to the intended start, never past it: a unit whose turn has already come runs
    * immediately and reports the delay as latency, which is the coordinated-omission correction
    * the gate exists to demonstrate.
    */
  private def sleepUntil(intendedStart: Instant): UIO[Unit] =
    Clock.instant.flatMap: now =>
      val wait = Duration.fromInterval(now, intendedStart)
      ZIO.sleep(wait).when(!wait.isNegative && !wait.isZero).unit

  private def started(current: CalibrationOutcomes): CalibrationOutcomes =
    current.copy(started = current.started + 1L)

  private def completed(current: CalibrationOutcomes): CalibrationOutcomes =
    current.copy(completed = current.completed + 1L)

  private def failed(current: CalibrationOutcomes): CalibrationOutcomes =
    current.copy(failed = current.failed + 1L)

object CalibrationLoop:
  /** The `scenario` label the gate's arrivals are counted under, so a calibration run is
    * distinguishable from a campaign on the same dashboards.
    */
  val scenarioLabel: String = "calibration"

  /** Where the per-arrival start delay is recorded. Named as a flow, and named distinctly from
    * the two profiles, so the merge cannot fold the driver's own lateness into the latency it is
    * being compared on.
    */
  val scheduleLagMeasurement: MeasurementId = MeasurementId.Flow("calibration-schedule-lag")

  /** How far ahead of the clock the generator may run. Bounded for `DriverLoop`'s reason -- it
    * back-pressures the generator, never the calls -- and large enough that the generator is not
    * woken per arrival at the rates a gate runs at.
    */
  val queueCapacity: Int = 1024

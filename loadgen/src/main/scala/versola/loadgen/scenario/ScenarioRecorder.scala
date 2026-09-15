package versola.loadgen.scenario

import versola.loadgen.metrics.*
import versola.loadgen.protocol.{FlowName, FlowObserver, ProtocolError, StepName}
import versola.loadgen.store.{DeferredUpdate, EventRow, WriteBehindBuffer}
import zio.*

import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

/** Where the protocol layer's hop timings become the campaign's numbers: the one implementation
  * of [[FlowObserver]] a driver runs with.
  *
  * The flow is the scenario. §11 labels a step with `(scenario, step)` and a flow with `(flow)`,
  * and the flows of §8 are exactly the things a session does -- a `mobile-otp` login, a `refresh`,
  * a `business-action`, a `step-up` -- so a separate scenario vocabulary beside [[FlowName]]
  * would be two names for one thing, free to disagree in a report nobody can then reconcile.
  *
  * **Why the intended start arrives through a `FiberRef`.** §7.2 measures all latency from the
  * instant an arrival was *scheduled* for, not from when a fiber got to it, and that instant is
  * known only to the driver loop. [[MobileFlows]] and [[WebFlows]] take their observer once, at
  * construction, and are shared by every session fiber, so the per-arrival value cannot be a
  * constructor parameter. A `FiberRef` is inherited by forked fibers and scoped by
  * [[forArrival]], which is the same lifetime as the session it describes.
  *
  * Only the *first* step and the first flow of an arrival are measured from the intended start.
  * That is where the driver's own queueing delay actually is: everything after it in the session
  * is separated by think time the scenario chose, so charging the start delay to each of them
  * would count one lateness once per hop and report a driver that is 200 ms behind as one whose
  * SUT is 200 ms slow at every step.
  */
final class ScenarioRecorder private (
    arrival: FiberRef[Option[ArrivalContext]],
    latencies: LatencyRecorder,
    buffer: WriteBehindBuffer,
    taxonomyRef: Ref[ErrorTaxonomy],
    abortRef: Ref[Option[CampaignAbort]],
    eventSampleRate: Double,
) extends FlowObserver:

  /** Runs one scheduled unit with its intended start in scope. */
  def forArrival[R, E, A](userId: Long, intendedStart: Instant)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    for
      pendingStep <- Ref.make(true)
      pendingFlow <- Ref.make(true)
      result <- arrival.locally(Some(ArrivalContext(userId, intendedStart, pendingStep, pendingFlow)))(effect)
    yield result

  override def step(flow: FlowName, step: StepName, elapsedNanos: Long, error: Option[ProtocolError]): UIO[Unit] =
    classify(error) match
      case Left(campaignAbort) => latch(campaignAbort)
      case Right(outcome) =>
        for
          context <- arrival.get
          latency <- latencyOf(context.map(_.pendingStep), elapsedNanos, context)
          _ <- latencies.record(MeasurementId.Step(flow.value, step.value), latency)
          _ <- taxonomyRef.update(_.record(outcome))
          _ <- record(flow, step, outcome, latency, error)
          _ <- sample(context, flow, step, outcome, latency)
        yield ()

  override def flow(flow: FlowName, elapsedNanos: Long, error: Option[ProtocolError]): UIO[Unit] =
    classify(error) match
      case Left(campaignAbort) => latch(campaignAbort)
      case Right(outcome) =>
        for
          context <- arrival.get
          latency <- latencyOf(context.map(_.pendingFlow), elapsedNanos, context)
          _ <- latencies.record(MeasurementId.Flow(flow.value), latency)
          _ <- LoadgenMetrics.flowCompleted(flow.value, outcome, latency)
        yield ()

  /** The taxonomy this driver has accumulated, for the snapshot the coordinator merges (§11). */
  def taxonomy: UIO[ErrorTaxonomy] = taxonomyRef.get

  /** Set once the emulator has measured something other than what it claims to (§4's
    * `Misconfigured`). The driver loop stops on it rather than continuing to produce numbers
    * nobody can defend; nothing clears it, because nothing about the run before it becomes
    * trustworthy again afterwards.
    */
  def abort: UIO[Option[CampaignAbort]] = abortRef.get

  private def classify(error: Option[ProtocolError]): Either[CampaignAbort, StepOutcome] =
    error match
      case None => Right(StepOutcome.ok)
      case Some(failure) => StepOutcome.of(failure)

  /** A refresh rejection is counted by its own metric and deliberately kept out of the duration
    * histograms' label set (§11, and [[LoadgenMetrics.stepCompleted]]'s contract): §7.4 ends the
    * session on one rather than treating it as one more step outcome. It is still in the
    * taxonomy above, because the error budget is defined over it.
    */
  private def record(
      flow: FlowName,
      step: StepName,
      outcome: StepOutcome,
      latency: IntendedLatency,
      error: Option[ProtocolError],
  ): UIO[Unit] =
    error match
      case Some(ProtocolError.RefreshRejected(reason)) => LoadgenMetrics.refreshRejected(reason)
      case _ => LoadgenMetrics.stepCompleted(flow.value, step.value, outcome, latency)

  private def latencyOf(
      pending: Option[Ref[Boolean]],
      elapsedNanos: Long,
      context: Option[ArrivalContext],
  ): UIO[IntendedLatency] =
    (pending, context) match
      case (Some(flag), Some(arrivalContext)) =>
        flag.getAndSet(false).flatMap:
          case true => Clock.instant.map(now => IntendedLatency.between(arrivalContext.intendedStart, now))
          case false => ZIO.succeed(IntendedLatency.unsafe(Duration.fromNanos(elapsedNanos)))
      // No arrival in scope: a calibration harness or a test driving the protocol client directly,
      // where there is no scheduled start to measure against and the hop's own elapsed time is the
      // only honest answer.
      case _ => ZIO.succeed(IntendedLatency.unsafe(Duration.fromNanos(elapsedNanos)))

  /** §6's 1% forensic sample, on the deferred path. `ThreadLocalRandom` because this decides
    * nothing about the load -- it picks which already-completed hops get written down -- and it
    * runs on every one of them.
    */
  private def sample(
      context: Option[ArrivalContext],
      flow: FlowName,
      step: StepName,
      outcome: StepOutcome,
      latency: IntendedLatency,
  ): UIO[Unit] =
    context match
      case Some(arrivalContext) if ThreadLocalRandom.current().nextDouble() < eventSampleRate =>
        Clock.instant.flatMap: now =>
          buffer.enqueue(
            DeferredUpdate.EventSampled(
              EventRow(
                at = now,
                userId = arrivalContext.userId,
                scenario = flow.value,
                step = step.value,
                outcome = outcome.label,
                latencyMs = (latency.micros / 1000L).toInt,
              ),
            ),
          )
      case _ => ZIO.unit

  /** The first abort is the one worth reading: every later hop of a misconfigured campaign
    * reports the same fault, and the run is over either way. Nothing else is recorded for it --
    * an emulator fault in the error budget's denominator is what makes the budget meaningless
    * ([[CampaignAbort]]).
    */
  private def latch(campaignAbort: CampaignAbort): UIO[Unit] =
    abortRef.update(_.orElse(Some(campaignAbort))) *>
      ZIO.logError(s"Campaign cannot produce a defensible number: ${campaignAbort.detail}")

private final case class ArrivalContext(
    userId: Long,
    intendedStart: Instant,
    pendingStep: Ref[Boolean],
    pendingFlow: Ref[Boolean],
)

object ScenarioRecorder:
  /** §6's `vu_events` sample rate: one hop in a hundred, which is what makes the table a forensic
    * aid rather than a second copy of the campaign.
    */
  val eventSampleRate: Double = 0.01

  def make(latencies: LatencyRecorder, buffer: WriteBehindBuffer, sampleRate: Double): URIO[Scope, ScenarioRecorder] =
    for
      arrival <- FiberRef.make(Option.empty[ArrivalContext])
      taxonomy <- Ref.make(ErrorTaxonomy.empty)
      abort <- Ref.make(Option.empty[CampaignAbort])
    yield ScenarioRecorder(arrival, latencies, buffer, taxonomy, abort, sampleRate)

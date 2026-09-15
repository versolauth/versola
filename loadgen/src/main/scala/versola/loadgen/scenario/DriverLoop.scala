package versola.loadgen.scenario

import versola.loadgen.metrics.{CampaignAbort, LoadgenMetrics}
import versola.loadgen.protocol.ProtocolError
import versola.loadgen.scheduler.*
import zio.*

import java.time.Instant

/** The driver's execution loop: §7.2's arrival process on one side, one fiber per virtual user on
  * the other.
  *
  * Two fibers and a bounded queue between them, which is the shape §7.2 asks for ("generated
  * ahead of the clock into a bounded queue of scheduled start times"):
  *
  *   - the **generator** advances [[ArrivalProcess]] under the campaign's rate envelope and fills
  *     the queue. It reads no clock. The recurrence is a pure function of the anchor and the
  *     draws, so the schedule exists whether or not the driver keeps up with it -- which is the
  *     only reason `now - intendedStart` measures anything.
  *   - the **dispatcher** takes the head, publishes it to [[ScheduleLag]], sleeps until its
  *     intended start, and forks the session.
  *
  * The queue is bounded so that the generator cannot run arbitrarily far ahead of a driver that
  * has stopped keeping up; it back-pressures the generator, never the sessions. That is the
  * opposite of the write-behind buffer's rule (§7.5) and for the same reason: delaying the
  * *generation* of a schedule that has not been reached yet costs nothing, while delaying a
  * session that is already late would hide the lateness the histograms exist to show.
  *
  * A session that fails is dropped, not retried. It has already been counted by
  * [[ScenarioRecorder]], and a retry is load the arrival process did not schedule.
  */
final class DriverLoop(
    arrivals: ArrivalProcess,
    rate: VaryingRate,
    pool: UserPool,
    busy: BusyUsers,
    runner: SessionRunner,
    recorder: ScenarioRecorder,
    lag: ScheduleLag,
    random: RandomSource,
    queueCapacity: Int,
):

  /** Runs until the campaign's horizon is reached or the fiber is interrupted, whichever comes
    * first. Interruption is the ordinary path: a drain, a shutdown, a rebalance.
    */
  def run: ZIO[Scope, Throwable, Unit] =
    for
      queue <- Queue.bounded[Option[ScheduledArrival]](queueCapacity)
      generator <- generate(queue).forkScoped
      _ <- dispatch(queue).ensuring(generator.interrupt)
    yield ()

  /** The generator's only exit is the horizon: [[ArrivalProcess.next]] answers `None` once the
    * campaign's last phase has ended.
    *
    * The horizon travels through the queue as a `None` rather than as a `shutdown`, so the
    * dispatcher reaches it only after the arrivals already queued ahead of it. Shutting the queue
    * down would discard them, and the campaign would stop short of its own schedule by however
    * far the generator had run ahead.
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
          // Nothing is waiting, so the gauge must say zero rather than hold the last arrival's
          // value -- a stale head reads as a driver falling behind while it is in fact idle.
          _ <- lag.observeHead(None).when(depth <= 0)
          // §4's `Misconfigured`: the campaign is measuring something other than what it claims,
          // and continuing would only produce more numbers nobody can defend. Checked here, once
          // per arrival, rather than by a polling fiber -- the loop is the only thing that would
          // act on it.
          aborted <- recorder.abort
          _ <- aborted match
            case Some(campaignAbort) => ZIO.fail(CampaignAbortedException(campaignAbort))
            case None => dispatch(queue)
        yield ()

  /** Forks the session and moves on. Not awaited: the dispatcher's job is to start work at the
    * instant the schedule says, and waiting for one session would turn the open model into a
    * closed one with a concurrency of exactly one.
    *
    * `forkScoped` ties the fiber to the loop's scope, so a shutdown interrupts every in-flight
    * session -- which is what makes a driver kill lose at most its in-flight users (§15) rather
    * than leaving sessions half-written.
    */
  private def start(arrival: ScheduledArrival): ZIO[Scope, Nothing, Unit] =
    pool.next.either.flatMap:
      case Left(error) => ZIO.logWarningCause("Could not pick a virtual user for a scheduled arrival", Cause.fail(error))
      case Right(None) => ZIO.logWarning("The driver's shard holds no registered virtual user to schedule")
      case Right(Some(user)) =>
        val fiberRandom = random.split()
        LoadgenMetrics.arrival(scenarioLabel) *>
          busy
            .withUser(user.id)(recorder.forArrival(user.id, arrival.intendedStart)(runner.run(user, fiberRandom)))
            .catchAll(reportDropped(user.id))
            .forkScoped
            .unit

  /** A session that ended in a protocol error is already in the taxonomy and the histograms; this
    * only says so in the log, at debug, because at a 0.1% error rate a campaign would otherwise
    * write several lines a second of something the report already counts.
    */
  private def reportDropped(userId: Long)(error: ProtocolError): UIO[Unit] =
    ZIO.logDebug(s"Session for virtual user $userId ended early: $error")

  /** Sleeps to the intended start, never past it. A unit whose turn has already come runs
    * immediately and reports the delay as latency (§7.2), which is the coordinated-omission
    * correction; sleeping a fixed interval instead would let the driver drift further behind
    * with every arrival while the histograms stayed flattering.
    */
  private def sleepUntil(intendedStart: Instant): UIO[Unit] =
    Clock.instant.flatMap: now =>
      val wait = Duration.fromInterval(now, intendedStart)
      ZIO.sleep(wait).when(!wait.isNegative && !wait.isZero).unit

  private val scenarioLabel = "session"

/** The one way a driver loop ends other than the horizon or an interruption. */
final case class CampaignAbortedException(campaignAbort: CampaignAbort)
  extends RuntimeException(s"campaign cannot produce a defensible number: ${campaignAbort.detail}")

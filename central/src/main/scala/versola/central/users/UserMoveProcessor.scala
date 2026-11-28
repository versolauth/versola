package versola.central.users

import zio.metrics.{Metric, MetricLabel}
import zio.{Cause, Clock, Duration, Fiber, IO, Ref, Schedule, Scope, Task, UIO, URIO, ZIO, ZLayer}

/** Background service that carries each move of a user between cells through its phases (versolauth/versola#1051, the driver of
  * #547 addendum 3.3), one phase at a time. The move has an id (`moveId`) that both cells are given, so that the arrival at the
  * target belongs to this departure and to no other:
  *
  *  1. `leaving`: the source cell is told the user is leaving ([[AuthClient.depart]]) and `settleFor` is waited, by scheduling:
  *     the move is not due again before then.
  *  1. `exported` and `arrived`: the user's rows are read from the source as an envelope central cannot open, with its digest, and
  *     handed to the target. The envelope is held in memory and only for as long as these two take, so a crash between them reads
  *     it again. The digest delivered is stored.
  *  1. before the flip, the source is asked again and its digest compared with the stored one. A difference is delivered again (the
  *     target replaces what it holds of this move) and checked again, [[UserMoveSettings.maxRedeliveries]] times at most; then the
  *     move is abandoned as `source_changed`.
  *  1. the flip: the directory names the target, and the move is `flipped`, in one transaction ([[UserMoveRepository.flip]]).
  *     From here the move is never rolled back.
  *  1. `done`: the target is told to keep the arrival ([[AuthClient.keepArrival]]), then the source cell drops what it held
  *     ([[AuthClient.completeDeparture]], with the digest of what the target was given, so that it can say when it dropped more).
  *
  * Every step is idempotent and a phase is stored only after the step succeeded, so a crash between the two resumes by repeating
  * the step. A failure before the flip is retried `maxAttempts` times with a backoff and then abandoned; a source that keeps
  * changing is abandoned at once. Abandoning is two writes, both guarded by the phase the instance saw: the move goes to `abandoning`
  * (of two instances, one flipping and one abandoning, exactly one update succeeds), and only the instance whose update succeeded
  * undoes the move (the target discards the arrival, the source serves the user again), after which the move is `failed`. A move
  * found `abandoning` by a later claim finishes the clean-up. A failure after the flip is retried without limit and never rolled
  * back: the target holds the user and the directory names it.
  *
  * No database transaction is open across a call to a cell. Safe to run on several instances: moves are leased by
  * [[UserMoveRepository.claimDue]] (`FOR UPDATE SKIP LOCKED`), a step repeated by two instances is idempotent and a phase change is
  * guarded by the phase the instance saw.
  */
trait UserMoveProcessor:
  def start(): URIO[Scope, Unit]
  def stop(): UIO[Unit]

object UserMoveProcessor:
  val live: ZLayer[UserMoveSettings & UserMoveRepository & AuthClient & Scope, Nothing, UserMoveProcessor] =
    ZLayer:
      ZIO.acquireRelease(
        acquire =
          for
            settings <- ZIO.service[UserMoveSettings]
            moves <- ZIO.service[UserMoveRepository]
            client <- ZIO.service[AuthClient]
            fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
            processor: UserMoveProcessor = Live(settings, moves, client, fiberRef)
          yield processor,
      )(_.stop())
        // Outside `acquire` on purpose, as for `UserOutboxProcessor`: acquire is uninterruptible and so would be the fiber.
        .tap(_.start())

  /** The steps of a move, which say what a failure was of. */
  private[users] enum Step:
    case Depart, Export, Arrive, Verify, Flip, Keep, Complete, Cleanup

  /** The source's rows went on changing for longer than the move may chase them: not a failure of a call, and not retried. */
  private[users] case object SourceChangedError extends RuntimeException("the user's rows at the source kept changing")

  /** A step that failed, and why. `at` is the move as the stored row has it while the step runs: the phase a failure is counted against,
    * which is not the one the pass began at when an earlier step of the same pass advanced the move. */
  private[users] final case class StepFailed(step: Step, error: Throwable, at: UserMove)

  /** The closed code a failed step is recorded under. */
  private[users] def errorCode(failed: StepFailed): String =
    (failed.step, failed.error) match
      case (_, _: AuthClient.CellNotRegistered) => MoveError.CellUnavailable
      case (_, SourceChangedError) => MoveError.SourceChanged
      case (Step.Depart, AuthClient.CellRefused(404, _)) => MoveError.SourceLacksUser
      case (Step.Depart, AuthClient.CellRefused(409, Some("departure_exists"))) => MoveError.DepartureExists
      case (Step.Arrive, AuthClient.CellRefused(409, _)) => MoveError.TargetHasUser
      case (Step.Arrive, AuthClient.CellRefused(400 | 413, Some("invalid_document"))) => MoveError.InvalidDocument
      case (Step.Arrive, AuthClient.CellRefused(400 | 413, _)) => MoveError.ArriveRejected
      case (Step.Depart, _) => MoveError.DepartFailed
      case (Step.Export | Step.Verify, _) => MoveError.ExportFailed
      case (Step.Arrive, _) => MoveError.ArriveFailed
      case (Step.Flip, _) => MoveError.FlipFailed
      case (Step.Keep, _) => MoveError.KeepFailed
      // what is left after the flip, and what is undone by an abandoned move, which keeps the code it was abandoned for
      case (Step.Complete | Step.Cleanup, _) => MoveError.CompleteFailed

  private val moves = Metric.gauge("central_user_moves")
  private val finished = Metric.counter("central_user_moves_finished_total")

  private[users] class Live(
      settings: UserMoveSettings,
      repo: UserMoveRepository,
      client: AuthClient,
      fiberRef: Ref[Option[Fiber.Runtime[Nothing, Unit]]],
  ) extends UserMoveProcessor:

    override def start(): URIO[Scope, Unit] =
      tick.repeat(Schedule.spaced(settings.pollInterval)).unit.forkScoped.flatMap(fiber => fiberRef.set(Some(fiber)))

    override def stop(): UIO[Unit] =
      fiberRef.get.flatMap(fiber => ZIO.foreachDiscard(fiber)(_.interrupt))

    /** Everything that is due, until nothing is: a move that has just made progress is due again at once. */
    private[users] def tick: UIO[Unit] =
      processOnce.repeatWhile(identity) *> report

    /** One pass over the moves that are due. @return whether there was any */
    private[users] def processOnce: UIO[Boolean] =
      (for
        now <- Clock.instant
        due <- repo.claimDue(now, settings.batchSize, settings.lease)
        _ <- ZIO
          .foreachParDiscard(due)(move =>
            step(move).catchAllCause(cause => ZIO.logErrorCause(s"user move ${move.userId} could not be worked on", cause)),
          )
          .withParallelism(settings.batchSize)
      yield due.nonEmpty).catchAllCause(cause => ZIO.logErrorCause("user_moves poll failed", cause).as(false))

    /** `central_user_moves{phase}`: how many moves are at each unfinished phase. A move that stays `flipped` is the alert. */
    private def report: UIO[Unit] =
      repo.countUnfinishedByPhase
        .flatMap(counts =>
          ZIO.foreachDiscard(MovePhase.unfinished)(phase =>
            moves.tagged(MetricLabel("phase", phase.code)).set(counts.getOrElse(phase, 0L).toDouble),
          ),
        )
        .catchAllCause(cause => ZIO.logWarningCause("user_moves gauge could not be read", cause))

    /** One move through whatever its phase asks for now. [[private[users]]] so that a test can hand an instance a move as another
      * instance saw it. */
    private[users] def step(move: UserMove): Task[Unit] =
      val run: IO[StepFailed, Unit] = move.phase match
        case MovePhase.Abandoning => cleaningUp(move)
        // the instance that gave up could not abandon the move, or died before it did
        case phase if phase != MovePhase.Flipped && move.attempts >= settings.maxAttempts =>
          abandon(move, move.error.getOrElse(MoveError.DepartFailed))
        case MovePhase.Leaving if move.departedAt.isEmpty => departing(move)
        case MovePhase.Leaving | MovePhase.Exported => exportAndArrive(move)
        case MovePhase.Arrived => verifyAndFlip(move)
        case MovePhase.Flipped => completing(move)
        case MovePhase.Done | MovePhase.Failed => ZIO.unit
      run.catchAll(failed => failure(failed))

    private def at[A](step: Step, move: UserMove)(effect: Task[A]): IO[StepFailed, A] = effect.mapError(StepFailed(step, _, move))

    private def departing(move: UserMove): IO[StepFailed, Unit] =
      for
        _ <- at(Step.Depart, move)(client.depart(move.fromCell, move.userId, move.toCell, move.moveId))
        now <- Clock.instant
        // the rows are read after the settle time, not now
        _ <- at(Step.Depart, move)(repo.markDeparted(move.userId, now, notBefore = now.plus(settings.settleFor)))
      yield ()

    private def exportAndArrive(move: UserMove): IO[StepFailed, Unit] =
      for
        exported <- at(Step.Export, move)(client.exportDeparture(move.fromCell, move.userId))
        advanced <-
          if move.phase == MovePhase.Leaving then advance(Step.Export, move, MovePhase.Leaving, MovePhase.Exported)
          else ZIO.succeed(true)
        // from here the stored row is `exported`, with no failed attempt of it
        stored = move.copy(phase = MovePhase.Exported, attempts = if move.phase == MovePhase.Leaving then 0 else move.attempts)
        _ <- ZIO.when(advanced):
          for
            _ <- at(Step.Arrive, stored)(client.arrive(move.toCell, move.userId, move.fromCell, move.moveId, exported.envelope))
            now <- Clock.instant
            _ <- at(Step.Arrive, stored)(repo.arrived(move.userId, exported.digest, now, notBefore = now))
          yield ()
      yield ()

    private def advance(step: Step, move: UserMove, from: MovePhase, to: MovePhase): IO[StepFailed, Boolean] =
      Clock.instant.flatMap(now => at(step, move)(repo.advance(move.userId, from, to, now, notBefore = now)))

    /** The source is asked for the rows once more and, if they are not the ones the target was given, the target is given these,
      * up to [[UserMoveSettings.maxRedeliveries]] times; then the directory is flipped. Nothing the source changes after this
      * answer reaches the target: that is the residual window, which the source reports at its completion (the digest). */
    private def verifyAndFlip(move: UserMove): IO[StepFailed, Unit] =
      verify(move, delivered = move.digest, redeliveries = 0) *> flipping(move)

    private def verify(move: UserMove, delivered: Option[String], redeliveries: Int): IO[StepFailed, Unit] =
      for
        current <- at(Step.Verify, move)(client.exportDeparture(move.fromCell, move.userId))
        _ <-
          if delivered.contains(current.digest) then ZIO.unit
          else if redeliveries >= settings.maxRedeliveries then ZIO.fail(StepFailed(Step.Verify, SourceChangedError, move))
          else
            for
              _ <- at(Step.Arrive, move)(client.arrive(move.toCell, move.userId, move.fromCell, move.moveId, current.envelope))
              now <- Clock.instant
              stored <- at(Step.Arrive, move)(repo.redelivered(move.userId, current.digest, now))
              // not at `arrived` any more: another instance took the move over, and what it does is not undone from here
              _ <- ZIO.when(stored)(verify(move, Some(current.digest), redeliveries + 1))
            yield ()
      yield ()

    private def flipping(move: UserMove): IO[StepFailed, Unit] =
      for
        now <- Clock.instant
        result <- at(Step.Flip, move)(repo.flip(move.userId, now, notBefore = now))
        _ <- result match
          case FlipResult.Flipped => ZIO.unit
          // somebody else's change of the directory: nothing is rolled forward, and what the move did is undone
          case FlipResult.NotInSource => abandon(move, MoveError.UserGone)
          // another instance got here first
          case FlipResult.NotArrived => ZIO.unit
      yield ()

    private def completing(move: UserMove): IO[StepFailed, Unit] =
      for
        _ <- at(Step.Keep, move)(unlessGone(client.keepArrival(move.toCell, move.userId, move.moveId)))
        // an empty digest says nothing was recorded (the row of a move that did not go through `arrived`): the source then reports
        // the rows as changed, which is the safe thing to say of rows nobody compared
        digest = move.digest.getOrElse("")
        _ <- at(Step.Complete, move)(unlessGone(client.completeDeparture(move.fromCell, move.userId, move.moveId, digest)))
        done <- advance(Step.Complete, move, MovePhase.Flipped, MovePhase.Done)
        _ <- ZIO.when(done)(finished.tagged(MetricLabel("outcome", "done")).increment)
      yield ()

    /** Gives up a move that is before its flip: the move goes to `abandoning` (guarded by the phase this instance saw, which is
      * never `flipped` or `done`), and only if that update was this instance's the move is undone: the target forgets the arrival,
      * the source serves the user again, and the move is `failed`. An instance that lost the update, because another one flipped or
      * abandoned the move first, changes nothing. */
    private def abandon(move: UserMove, error: String): IO[StepFailed, Unit] =
      for
        now <- Clock.instant
        won <- at(Step.Cleanup, move)(repo.beginAbandon(move.userId, move.phase, error, now, notBefore = now))
        _ <- ZIO.when(won)(cleaningUp(move.copy(phase = MovePhase.Abandoning, error = Some(error))))
      yield ()

    /** The clean-up of an abandoned move, and the end of it. */
    private def cleaningUp(move: UserMove): IO[StepFailed, Unit] =
      for
        _ <- at(Step.Cleanup, move)(unlessGone(client.discardArrival(move.toCell, move.userId, move.moveId)))
        _ <- at(Step.Cleanup, move)(unlessGone(client.cancelDeparture(move.fromCell, move.userId)))
        now <- Clock.instant
        over <- at(Step.Cleanup, move)(repo.abandoned(move.userId, now))
        _ <- ZIO.when(over):
          finished.tagged(MetricLabel("outcome", "failed")).increment *>
            ZIO.logWarning(
              s"user move ${move.userId} ${move.fromCell} -> ${move.toCell} was abandoned: ${move.error.getOrElse(MoveError.DepartFailed)}",
            )
      yield ()

    /** A cell that is not registered any more (it was removed) holds nothing to undo or to drop: its part of a move is over. */
    private def unlessGone(call: Task[Unit]): Task[Unit] =
      call.catchSome { case _: AuthClient.CellNotRegistered => ZIO.unit }

    /** A step failed: counted, scheduled again after a backoff, and abandoned when it was the last attempt before the flip, or at
      * once when the source's rows will not hold still. The clean-up of an abandoned move, like a step after the flip, is retried
      * without limit. */
    private def failure(failed: StepFailed): Task[Unit] =
      val move = failed.at
      val attempts = move.attempts + 1
      val code = if failed.step == Step.Cleanup then move.error.getOrElse(errorCode(failed)) else errorCode(failed)
      def cleanedUpLater(abandoned: IO[StepFailed, Unit]): Task[Unit] = abandoned.catchAll(failure)
      if failed.error == SourceChangedError then
        ZIO.logWarning(
          s"user move ${move.userId} ${move.fromCell} -> ${move.toCell}: the source changed ${settings.maxRedeliveries + 1} times in a row",
        ) *>
          cleanedUpLater(abandon(move, code))
      else
        for
          now <- Clock.instant
          _ <- ZIO.logWarningCause(
            s"user move ${move.userId} ${move.fromCell} -> ${move.toCell}: ${failed.step} failed (attempt $attempts, $code)",
            Cause.fail(failed.error),
          )
          _ <- repo.retry(move.userId, move.phase, attempts, code, now, notBefore = now.plus(backoff(attempts)))
          // the last attempt before the flip: the move is given up now rather than at the next claim
          _ <- ZIO.when(beforeFlip(move.phase) && attempts >= settings.maxAttempts):
            cleanedUpLater(abandon(move.copy(attempts = attempts), code))
        yield ()

    private def beforeFlip(phase: MovePhase): Boolean =
      phase == MovePhase.Leaving || phase == MovePhase.Exported || phase == MovePhase.Arrived

    private def backoff(attempts: Int): Duration =
      Duration.fromSeconds(math.min(math.pow(2.0, attempts.toDouble).toLong, settings.maxBackoff.toSeconds))

package versola.central.users

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.topology.CellId
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The driver of a move between cells (versolauth/versola#1051): each phase in turn against stubbed cells, a crash between any two
  * writes, the retries before and after the flip, and the settle time kept by the clock and not by a sleep. */
object UserMoveProcessorSpec extends ZIOSpecDefault, ZIOStubs:

  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val source = CellId("kz-1")
  private val target = CellId("kz-2")
  private val settings = UserMoveSettings()
  private val moveId = UUID.fromString("00000000-0000-7000-8000-0000000000a1")
  private val envelope = "envelope-1"
  private val digest = "digest-1"
  private val sealedRows = AuthClient.DepartureExport(envelope, digest)

  private case class Fixture(moves: InMemoryUserMoves, client: Stub[AuthClient], processor: UserMoveProcessor.Live)

  private def fixture: UIO[Fixture] = InMemoryUserMoves.make.flatMap(fixtureOver)

  /** An instance of the processor, with cells of its own, over moves another instance works on too. */
  private def fixtureOver(moves: InMemoryUserMoves): UIO[Fixture] =
    for
      client = stub[AuthClient]
      fiber <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
    yield Fixture(moves, client, UserMoveProcessor.Live(settings, moves, client, fiber))

  private def newMove(phase: MovePhase, now: Instant) =
    UserMove(
      userId = userId,
      fromCell = source,
      toCell = target,
      phase = phase,
      startedAt = now,
      updatedAt = now,
      attempts = 0,
      error = None,
      reason = MoveReason.Admin,
      notBefore = now,
      departedAt = None,
      moveId = moveId,
      // a move that is at or past `arrived` has been given the rows, and has their digest
      digest = Option.when(phase == MovePhase.Arrived || phase == MovePhase.Flipped || phase == MovePhase.Done)(digest),
    )

  /** The user is in the source cell and a move of it to the target is recorded at `phase`. */
  private def begun(f: Fixture, phase: MovePhase = MovePhase.Leaving) =
    for
      now <- Clock.instant
      _ <- f.moves.place(userId, Some(source))
      _ <- f.moves.start(newMove(phase, now))
    yield ()

  private def cellsAnswer(f: Fixture) =
    f.client.depart.succeedsWith(()) *>
      f.client.exportDeparture.succeedsWith(sealedRows) *>
      f.client.arrive.succeedsWith(()) *>
      f.client.keepArrival.succeedsWith(()) *>
      f.client.completeDeparture.succeedsWith(()) *>
      f.client.cancelDeparture.succeedsWith(()) *>
      f.client.discardArrival.succeedsWith(())

  private def phaseOf(f: Fixture) = f.moves.find(userId).map(_.map(_.phase))

  /** Passes over the moves until none is due, with the clock moving past every lease and backoff in between. */
  private def untilDone(f: Fixture, passes: Int = 12) =
    ZIO.foreachDiscard(1 to passes)(_ => f.processor.tick *> TestClock.adjust(70.seconds))

  private def failedAt(attempts: Int, f: Fixture) =
    ZIO.foreachDiscard(1 to attempts)(_ => f.processor.processOnce *> TestClock.adjust(settings.maxBackoff + 1.second))

  def spec = suite("UserMoveProcessor")(
    suite("the phases in turn")(
      test("the source is told first and the rows are read only after the settle time, which the clock waits and no sleep does") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f)
          told <- f.processor.processOnce
          afterTold <- f.moves.find(userId)
          nothingDue <- f.processor.processOnce
          _ <- TestClock.adjust(1.second)
          stillSettling <- f.processor.processOnce
          _ <- TestClock.adjust(1.second)
          read <- f.processor.processOnce
        yield assertTrue(
          told,
          f.client.depart.calls == List((source, userId, target, moveId)),
          afterTold.map(m => (m.phase, m.departedAt.isDefined)).contains((MovePhase.Leaving, true)),
          // nothing is read, handed over or flipped while the settle time runs
          !nothingDue,
          !stillSettling,
          read,
          f.client.exportDeparture.calls == List((source, userId)),
        )
      },
      test("the envelope the source sealed is handed to the target as it is, and the move is then arrived") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f)
          _ <- f.processor.processOnce
          _ <- TestClock.adjust(settings.settleFor)
          _ <- f.processor.processOnce
          phase <- phaseOf(f)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          f.client.arrive.calls == List((target, userId, source, moveId, envelope)),
          phase.contains(MovePhase.Arrived),
          // the directory is not changed until the flip
          directory.contains(source),
          f.client.completeDeparture.calls.isEmpty,
        )
      },
      test("the flip gives the user to the target in the directory, and only then is the source told to drop it") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f, MovePhase.Arrived)
          _ <- f.processor.processOnce
          flipped <- phaseOf(f)
          directory <- f.moves.cellOf(userId)
          completedBefore = f.client.completeDeparture.calls
          _ <- f.processor.processOnce
          done <- phaseOf(f)
        yield assertTrue(
          flipped.contains(MovePhase.Flipped),
          directory.contains(target),
          completedBefore.isEmpty,
          done.contains(MovePhase.Done),
          f.client.completeDeparture.calls == List((source, userId, moveId, digest)),
          // nothing is undone on the way
          f.client.cancelDeparture.calls.isEmpty,
          f.client.discardArrival.calls.isEmpty,
        )
      },
      test("a move that runs to the end ends done with each cell told exactly once") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f)
          _ <- untilDone(f, passes = 3)
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          move.map(m => (m.phase, m.attempts, m.error)).contains((MovePhase.Done, 0, None)),
          directory.contains(target),
          f.client.depart.calls == List((source, userId, target, moveId)),
          // read once, and once more before the flip to see that the rows are the ones that were delivered
          f.client.exportDeparture.calls == List((source, userId), (source, userId)),
          f.client.arrive.calls == List((target, userId, source, moveId, envelope)),
          f.client.keepArrival.calls == List((target, userId, moveId)),
          f.client.completeDeparture.calls == List((source, userId, moveId, digest)),
        )
      },
      test("a finished or abandoned move is left alone") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f, MovePhase.Done)
          due <- f.processor.processOnce
        yield assertTrue(!due, f.client.depart.calls.isEmpty, f.client.completeDeparture.calls.isEmpty)
      },
    ),
    suite("a crash between two writes resumes and ends done")(
      // the writes of a move that runs through are: the departure marked, exported, arrived, the flip, done
      ((for
        write <- List(1, 2, 3, 4, 5)
        applied <- List(false, true)
      yield test(s"the process dies at write $write, ${if applied then "after it took effect" else "before it did"}") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f)
          _ <- f.moves.crashAt(write, applied)
          // the first run: it stops where it was killed, and a settle time passes
          _ <- ZIO.foreachDiscard(1 to 3)(_ => f.processor.tick *> TestClock.adjust(settings.settleFor))
          _ <- f.moves.revive
          _ <- TestClock.adjust(settings.lease + 1.second)
          _ <- untilDone(f)
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          move.map(_.phase).contains(MovePhase.Done),
          directory.contains(target),
          // what each cell received is what an idempotent repeat of it would be: the same call, possibly again, and never an undo
          f.client.depart.calls.nonEmpty && f.client.depart.calls.distinct == List((source, userId, target, moveId)),
          f.client.exportDeparture.calls.nonEmpty && f.client.exportDeparture.calls.distinct == List((source, userId)),
          f.client.arrive.calls.nonEmpty && f.client.arrive.calls.distinct == List((target, userId, source, moveId, envelope)),
          f.client.completeDeparture.calls.nonEmpty && f.client.completeDeparture.calls.distinct == List((source, userId, moveId, digest)),
          f.client.keepArrival.calls.nonEmpty && f.client.keepArrival.calls.distinct == List((target, userId, moveId)),
          f.client.cancelDeparture.calls.isEmpty,
          f.client.discardArrival.calls.isEmpty,
        )
      }) ++ (for
        write <- List(1, 2, 3, 4, 5, 6)
        applied <- List(false, true)
      yield test(
        s"with rows the source changed meanwhile, the process dies at write $write, ${if applied then "after it took effect" else "before it did"}",
      ) {
        // the writes are: the departure marked, exported, arrived, the digest of the second delivery, the flip, done
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.exportDeparture.returnsZIOOnCall:
            case 1 => ZIO.succeed(AuthClient.DepartureExport("envelope-1", "digest-1"))
            case _ => ZIO.succeed(AuthClient.DepartureExport("envelope-2", "digest-2"))
          _ <- begun(f)
          _ <- f.moves.crashAt(write, applied)
          _ <- ZIO.foreachDiscard(1 to 4)(_ => f.processor.tick *> TestClock.adjust(settings.settleFor))
          _ <- f.moves.revive
          _ <- TestClock.adjust(settings.lease + 1.second)
          _ <- untilDone(f)
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          move.map(m => (m.phase, m.digest)).contains((MovePhase.Done, Some("digest-2"))),
          directory.contains(target),
          // what the target holds in the end is the later delivery, and the source is told the digest of that one
          f.client.arrive.calls.lastOption.contains((target, userId, source, moveId, "envelope-2")),
          f.client.completeDeparture.calls.nonEmpty && f.client.completeDeparture.calls.forall(_ == ((source, userId, moveId, "digest-2"))),
          f.client.keepArrival.calls.nonEmpty && f.client.keepArrival.calls.distinct == List((target, userId, moveId)),
          f.client.cancelDeparture.calls.isEmpty,
          f.client.discardArrival.calls.isEmpty,
        )
      }) ++ List(test("a crash between the export and the arrival reads the rows again: the envelope is held in memory and nowhere else") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f)
          // writes: 1 departure marked, 2 exported, 3 arrived
          _ <- f.moves.crashAt(3, applied = false)
          _ <- ZIO.foreachDiscard(1 to 3)(_ => f.processor.tick *> TestClock.adjust(settings.settleFor))
          stored <- phaseOf(f)
          _ <- f.moves.revive
          _ <- TestClock.adjust(settings.lease + 1.second)
          _ <- untilDone(f)
        yield assertTrue(stored.contains(MovePhase.Exported), f.client.exportDeparture.calls.size == 3, f.client.arrive.calls.size == 2)
      }))*,
    ),
    suite("a failure before the flip")(
      test("of a move whose cells were removed meanwhile is abandoned all the same: a cell that is gone has nothing to undo") {
        for
          f <- fixture
          _ <- f.client.depart.failsWith(AuthClient.CellNotRegistered(source))
          _ <- f.client.discardArrival.failsWith(AuthClient.CellNotRegistered(target))
          _ <- f.client.cancelDeparture.failsWith(AuthClient.CellNotRegistered(source))
          _ <- begun(f)
          _ <- failedAt(settings.maxAttempts, f)
          move <- f.moves.find(userId)
        yield assertTrue(move.map(m => (m.phase, m.error)).contains((MovePhase.Failed, Some("cell_unavailable"))))
      },
      test("after the flip, a source cell that was removed has nothing to drop: the move is done") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.completeDeparture.failsWith(AuthClient.CellNotRegistered(source))
          _ <- begun(f, MovePhase.Arrived)
          _ <- f.processor.processOnce
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(move.map(_.phase).contains(MovePhase.Done), directory.contains(target))
      },
      test("is retried with a backoff, MaxAttempts times, and then abandoned: the arrival is discarded and the departure cancelled") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.depart.failsWith(AuthClient.CellRefused(503))
          _ <- begun(f)
          _ <- failedAt(settings.maxAttempts - 1, f)
          retrying <- f.moves.find(userId)
          _ <- failedAt(1, f)
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          retrying.map(m => (m.phase, m.attempts, m.error)).contains((MovePhase.Leaving, 4, Some("depart_failed"))),
          f.client.depart.calls.size == settings.maxAttempts,
          move.map(m => (m.phase, m.error)).contains((MovePhase.Failed, Some("depart_failed"))),
          f.client.discardArrival.calls == List((target, userId, moveId)),
          f.client.cancelDeparture.calls == List((source, userId)),
          // the user is where it was, and nothing was read or handed over
          directory.contains(source),
          f.client.exportDeparture.calls.isEmpty,
          f.client.arrive.calls.isEmpty,
        )
      },
      test("is not retried before its backoff has passed") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.depart.failsWith(AuthClient.CellRefused(503))
          _ <- begun(f)
          _ <- f.processor.processOnce
          early <- f.processor.processOnce
          _ <- TestClock.adjust(1.second)
          stillEarly <- f.processor.processOnce
          _ <- TestClock.adjust(2.seconds)
          due <- f.processor.processOnce
        yield assertTrue(!early, !stillEarly, due, f.client.depart.calls.size == 2)
      },
      test("of the arrival leaves the move exported, and is recorded with the code of what went wrong, not what the cell said") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.arrive.failsWith(AuthClient.CellRefused(500))
          _ <- begun(f)
          _ <- f.processor.processOnce
          _ <- TestClock.adjust(settings.settleFor)
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
        yield assertTrue(move.map(m => (m.phase, m.attempts, m.error)).contains((MovePhase.Exported, 1, Some("arrive_failed"))))
      },
      test("a target that already holds the user is recorded as that, and a source that does not is too") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.arrive.failsWith(AuthClient.CellRefused(409))
          _ <- begun(f)
          _ <- f.processor.processOnce
          _ <- TestClock.adjust(settings.settleFor)
          _ <- f.processor.processOnce
          conflict <- f.moves.find(userId)
          g <- fixture
          _ <- cellsAnswer(g)
          _ <- g.client.depart.failsWith(AuthClient.CellRefused(404))
          _ <- begun(g)
          _ <- g.processor.processOnce
          missing <- g.moves.find(userId)
          h <- fixture
          _ <- cellsAnswer(h)
          _ <- h.client.depart.failsWith(AuthClient.CellNotRegistered(source))
          _ <- begun(h)
          _ <- h.processor.processOnce
          unregistered <- h.moves.find(userId)
        yield assertTrue(
          conflict.flatMap(_.error).contains("target_has_user"),
          missing.flatMap(_.error).contains("source_lacks_user"),
          unregistered.flatMap(_.error).contains("cell_unavailable"),
        )
      },
      test("whose clean-up fails is abandoning, with the reason kept, and is cleaned up at the next claim, then failed") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.depart.failsWith(AuthClient.CellRefused(503))
          _ <- f.client.discardArrival.returnsZIOOnCall:
            case 1 => ZIO.fail(AuthClient.CellRefused(503))
            case _ => ZIO.unit
          _ <- begun(f)
          _ <- failedAt(settings.maxAttempts, f)
          stuck <- f.moves.find(userId)
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
        yield assertTrue(
          // the move is given up: the source is not asked to depart again, and the reason of it is kept while the clean-up is retried
          stuck.map(m => (m.phase, m.error)).contains((MovePhase.Abandoning, Some("depart_failed"))),
          move.map(m => (m.phase, m.error)).contains((MovePhase.Failed, Some("depart_failed"))),
          f.client.discardArrival.calls.size == 2,
          f.client.cancelDeparture.calls == List((source, userId)),
          f.client.depart.calls.size == settings.maxAttempts,
        )
      },
      test("an abandoning move is cleaned up without limit, backing off, and does not lose the reason it was given up for") {
        val failures = 12
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.cancelDeparture.returnsZIOOnCall:
            case n if n <= failures => ZIO.fail(AuthClient.CellRefused(503))
            case _ => ZIO.unit
          _ <- begun(f)
          _ <- f.moves.beginAbandon(userId, MovePhase.Leaving, MoveError.ArriveRejected, Instant.EPOCH, Instant.EPOCH)
          _ <- failedAt(failures, f)
          stuck <- f.moves.find(userId)
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
        yield assertTrue(
          stuck.map(m => (m.phase, m.error)).contains((MovePhase.Abandoning, Some("arrive_rejected"))),
          move.map(m => (m.phase, m.error)).contains((MovePhase.Failed, Some("arrive_rejected"))),
          f.client.cancelDeparture.calls.size == failures + 1,
          // nothing else of the move is done meanwhile
          f.client.depart.calls.isEmpty,
          f.client.arrive.calls.isEmpty,
          f.client.keepArrival.calls.isEmpty,
          f.client.completeDeparture.calls.isEmpty,
        )
      },
      test("of the directory, which no longer names the source cell, abandons the move at once and undoes it: nothing is retried") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f, MovePhase.Arrived)
          // somebody deleted the user while the move was under way
          _ <- f.moves.unplace(userId)
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          move.map(m => (m.phase, m.error)).contains((MovePhase.Failed, Some("user_gone"))),
          directory.isEmpty,
          f.client.discardArrival.calls == List((target, userId, moveId)),
          f.client.cancelDeparture.calls == List((source, userId)),
          f.client.completeDeparture.calls.isEmpty,
        )
      },
      test("of the directory, which names a third cell, is not flipped over") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f, MovePhase.Arrived)
          _ <- f.moves.place(userId, Some(CellId("kz-3")))
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(move.map(_.phase).contains(MovePhase.Failed), directory.contains(CellId("kz-3")))
      },
    ),
    suite("a failure after the flip")(
      test("is retried without limit and never rolled back, and the move is done once the source answers") {
        val failures = 12
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.completeDeparture.returnsZIOOnCall:
            case n if n <= failures => ZIO.fail(AuthClient.CellRefused(503))
            case _ => ZIO.unit
          _ <- begun(f, MovePhase.Arrived)
          _ <- f.processor.processOnce
          _ <- failedAt(failures, f)
          stuck <- f.moves.find(userId)
          directoryWhileStuck <- f.moves.cellOf(userId)
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
        yield assertTrue(
          // far past MaxAttempts, and still flipped
          stuck.map(m => (m.phase, m.attempts, m.error)).contains((MovePhase.Flipped, failures, Some("complete_failed"))),
          directoryWhileStuck.contains(target),
          move.map(m => (m.phase, m.attempts, m.error)).contains((MovePhase.Done, 0, None)),
          f.client.completeDeparture.calls.size == failures + 1,
          // the target is told to keep the arrival each time the source is asked again: idempotent, and in front of it
          f.client.keepArrival.calls.size == failures + 1,
          f.client.cancelDeparture.calls.isEmpty,
          f.client.discardArrival.calls.isEmpty,
        )
      },
      test("a target that does not confirm the arrival is kept is asked again without limit, and the source drops nothing meanwhile") {
        val failures = 12
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.keepArrival.returnsZIOOnCall:
            case n if n <= failures => ZIO.fail(AuthClient.CellRefused(409, Some("no_arrival")))
            case _ => ZIO.unit
          _ <- begun(f, MovePhase.Arrived)
          _ <- f.processor.processOnce
          _ <- failedAt(failures, f)
          stuck <- f.moves.find(userId)
          droppedMeanwhile = f.client.completeDeparture.calls
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          stuck.map(m => (m.phase, m.attempts, m.error)).contains((MovePhase.Flipped, failures, Some("keep_failed"))),
          droppedMeanwhile.isEmpty,
          move.map(_.phase).contains(MovePhase.Done),
          directory.contains(target),
          f.client.keepArrival.calls.distinct == List((target, userId, moveId)),
          f.client.completeDeparture.calls == List((source, userId, moveId, digest)),
          f.client.cancelDeparture.calls.isEmpty,
          f.client.discardArrival.calls.isEmpty,
        )
      },
      test("a target that is no longer registered has nothing to keep: the source is told to drop all the same") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.keepArrival.failsWith(AuthClient.CellNotRegistered(target))
          _ <- begun(f, MovePhase.Arrived)
          _ <- f.processor.processOnce
          _ <- f.processor.processOnce
          move <- f.moves.find(userId)
        yield assertTrue(move.map(_.phase).contains(MovePhase.Done), f.client.completeDeparture.calls.size == 1)
      },
      test("waits at most the longest backoff between attempts") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.completeDeparture.failsWith(AuthClient.CellRefused(503))
          _ <- begun(f, MovePhase.Arrived)
          _ <- f.processor.processOnce
          _ <- failedAt(20, f)
          // the clock moved one backoff past each attempt, so the next is due however many there were
          due <- f.processor.processOnce
          move <- f.moves.find(userId)
        yield assertTrue(due, move.map(_.attempts).contains(21), move.map(_.phase).contains(MovePhase.Flipped))
      },
    ),
    suite("the rows are asked for again before the flip, and what the source changed is delivered again")(
      test("rows that did not change are not delivered again: one delivery, and the digest stored is the one of the export") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f)
          _ <- untilDone(f, passes = 3)
          move <- f.moves.find(userId)
        yield assertTrue(
          f.client.exportDeparture.calls.size == 2,
          f.client.arrive.calls == List((target, userId, source, moveId, envelope)),
          move.map(m => (m.phase, m.digest)).contains((MovePhase.Done, Some(digest))),
        )
      },
      test(
        "rows the source changed meanwhile are delivered again under the same move id, checked again, and the new digest is what is stored and reported",
      ) {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.exportDeparture.returnsZIOOnCall:
            case 1 => ZIO.succeed(AuthClient.DepartureExport("envelope-1", "digest-1"))
            case _ => ZIO.succeed(AuthClient.DepartureExport("envelope-2", "digest-2"))
          _ <- begun(f)
          _ <- untilDone(f, passes = 4)
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          f.client.arrive.calls == List(
            (target, userId, source, moveId, "envelope-1"),
            (target, userId, source, moveId, "envelope-2"),
          ),
          // asked once to read, once to check, once to check what was delivered again
          f.client.exportDeparture.calls.size == 3,
          move.map(m => (m.phase, m.digest)).contains((MovePhase.Done, Some("digest-2"))),
          f.client.completeDeparture.calls == List((source, userId, moveId, "digest-2")),
          directory.contains(target),
          f.client.discardArrival.calls.isEmpty,
        )
      },
      test("rows that never hold still are chased MaxRedeliveries times and then the move is abandoned as source_changed, at once and undone") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.exportDeparture.returnsZIOOnCall(n => ZIO.succeed(AuthClient.DepartureExport(s"envelope-$n", s"digest-$n")))
          _ <- begun(f)
          _ <- untilDone(f, passes = 4)
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          move.map(m => (m.phase, m.error)).contains((MovePhase.Failed, Some("source_changed"))),
          // one delivery, then one for each time the check found a difference, the last one being checked too
          f.client.arrive.calls.size == 1 + settings.maxRedeliveries,
          f.client.exportDeparture.calls.size == 2 + settings.maxRedeliveries,
          // no retry with a backoff: the departure was told once, and the user is served again
          f.client.depart.calls.size == 1,
          f.client.discardArrival.calls == List((target, userId, moveId)),
          f.client.cancelDeparture.calls == List((source, userId)),
          directory.contains(source),
          f.client.keepArrival.calls.isEmpty,
          f.client.completeDeparture.calls.isEmpty,
        )
      },
      test("a source that cannot be asked for the check is retried like any step before the flip, and is not flipped meanwhile") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- f.client.exportDeparture.failsWith(AuthClient.CellRefused(503))
          _ <- begun(f, MovePhase.Arrived)
          _ <- failedAt(2, f)
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          move.map(m => (m.phase, m.attempts, m.error)).contains((MovePhase.Arrived, 2, Some("export_failed"))),
          directory.contains(source),
          f.client.arrive.calls.isEmpty,
        )
      },
      test("a delivery that the target refuses at the check is recorded by what it was, and the move is abandoned once the attempts are used") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          // the move holds digest-1 already: what the source says now is another
          _ <- f.client.exportDeparture.returnsZIOOnCall(n => ZIO.succeed(AuthClient.DepartureExport(s"envelope-$n", s"digest-${n + 1}")))
          _ <- f.client.arrive.failsWith(AuthClient.CellRefused(400, Some("invalid_document")))
          _ <- begun(f, MovePhase.Arrived)
          _ <- failedAt(settings.maxAttempts, f)
          move <- f.moves.find(userId)
        yield assertTrue(move.map(m => (m.phase, m.error)).contains((MovePhase.Failed, Some("invalid_document"))))
      },
    ),
    suite("the answer of a cell is recorded under a code of the processor's own")(
      test("every refusal of the target and of the source maps to a code of the closed set, and none carries what the cell said") {
        val cases = List(
          (UserMoveProcessor.Step.Depart, AuthClient.CellRefused(404, None), MoveError.SourceLacksUser),
          (UserMoveProcessor.Step.Depart, AuthClient.CellRefused(409, Some("departure_exists")), MoveError.DepartureExists),
          (UserMoveProcessor.Step.Depart, AuthClient.CellRefused(409, Some("something_else")), MoveError.DepartFailed),
          (UserMoveProcessor.Step.Arrive, AuthClient.CellRefused(409, Some("user_exists")), MoveError.TargetHasUser),
          (UserMoveProcessor.Step.Arrive, AuthClient.CellRefused(400, Some("invalid_document")), MoveError.InvalidDocument),
          (UserMoveProcessor.Step.Arrive, AuthClient.CellRefused(400, Some("envelope_other_move")), MoveError.ArriveRejected),
          (UserMoveProcessor.Step.Arrive, AuthClient.CellRefused(413, None), MoveError.ArriveRejected),
          (UserMoveProcessor.Step.Arrive, AuthClient.CellRefused(503, None), MoveError.ArriveFailed),
          (UserMoveProcessor.Step.Verify, AuthClient.CellRefused(503, None), MoveError.ExportFailed),
          (UserMoveProcessor.Step.Verify, UserMoveProcessor.SourceChangedError, MoveError.SourceChanged),
          (UserMoveProcessor.Step.Keep, AuthClient.CellRefused(409, Some("no_arrival")), MoveError.KeepFailed),
          (UserMoveProcessor.Step.Complete, AuthClient.CellRefused(500, None), MoveError.CompleteFailed),
          (UserMoveProcessor.Step.Arrive, AuthClient.CellNotRegistered(target), MoveError.CellUnavailable),
        )
        val at = newMove(MovePhase.Leaving, Instant.EPOCH)
        val codes = cases.map((step, error, expected) => (UserMoveProcessor.errorCode(UserMoveProcessor.StepFailed(step, error, at)), expected))
        assertTrue(codes.forall(_ == _), codes.forall((code, _) => MoveError.all.contains(code)))
      },
    ),
    suite("two instances that reach for the same move")(
      test("one abandons while the other flips: the abandon wins when it comes first, and the other changes nothing") {
        for
          f <- fixture
          other <- fixtureOver(f.moves)
          _ <- cellsAnswer(f)
          _ <- cellsAnswer(other)
          _ <- begun(f, MovePhase.Arrived)
          seen <- f.moves.find(userId).someOrFailException
          // both saw the move at `arrived`; the one that abandons has used up the attempts
          _ <- f.processor.step(seen.copy(attempts = settings.maxAttempts))
          _ <- other.processor.step(seen)
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          move.map(_.phase).contains(MovePhase.Failed),
          directory.contains(source),
          f.client.discardArrival.calls == List((target, userId, moveId)),
          f.client.cancelDeparture.calls == List((source, userId)),
          // the loser flipped nothing, kept nothing, and dropped nothing at the source
          other.client.keepArrival.calls.isEmpty,
          other.client.completeDeparture.calls.isEmpty,
          other.client.discardArrival.calls.isEmpty,
          other.client.cancelDeparture.calls.isEmpty,
        )
      },
      test("one abandons while the other flips: the flip wins when it comes first, and the abandon undoes nothing") {
        for
          f <- fixture
          other <- fixtureOver(f.moves)
          _ <- cellsAnswer(f)
          _ <- cellsAnswer(other)
          _ <- begun(f, MovePhase.Arrived)
          seen <- f.moves.find(userId).someOrFailException
          _ <- other.processor.step(seen)
          flipped <- f.moves.find(userId)
          _ <- f.processor.step(seen.copy(attempts = settings.maxAttempts))
          move <- f.moves.find(userId)
          directory <- f.moves.cellOf(userId)
        yield assertTrue(
          flipped.map(_.phase).contains(MovePhase.Flipped),
          move.map(_.phase).contains(MovePhase.Flipped),
          directory.contains(target),
          // the one that lost the update told neither cell anything
          f.client.discardArrival.calls.isEmpty,
          f.client.cancelDeparture.calls.isEmpty,
        )
      },
      test("a move is never abandoned from flipped, whatever an instance believes: the attempts do not matter after the flip") {
        for
          f <- fixture
          _ <- cellsAnswer(f)
          _ <- begun(f, MovePhase.Arrived)
          _ <- f.processor.processOnce
          flipped <- f.moves.find(userId).someOrFailException
          _ <- f.processor.step(flipped.copy(attempts = settings.maxAttempts + 10))
          move <- f.moves.find(userId)
        yield assertTrue(
          move.map(_.phase).contains(MovePhase.Done),
          f.client.discardArrival.calls.isEmpty,
          f.client.cancelDeparture.calls.isEmpty,
        )
      },
    ),
    test("a move is not claimed by a second pass while the first still holds its lease") {
      for
        f <- fixture
        _ <- cellsAnswer(f)
        _ <- begun(f)
        first <- f.moves.claimDue(Instant.EPOCH, 10, settings.lease)
        second <- f.moves.claimDue(Instant.EPOCH, 10, settings.lease)
      yield assertTrue(first.size == 1, second.isEmpty)
    },
  ) @@ TestAspect.silentLogging

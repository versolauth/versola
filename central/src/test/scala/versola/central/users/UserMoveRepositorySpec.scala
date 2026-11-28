package versola.central.users

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.topology.CellId
import versola.util.{DatabaseSpecBase, Email, Secret}
import zio.test.*
import zio.{Duration, ZIO}

import java.time.Instant
import java.util.UUID

/** What central keeps of the moves of users between cells (versolauth/versola#1051), held to by every storage. */
trait UserMoveRepositorySpec extends DatabaseSpecBase[UserMoveRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private def user(n: Int) = UserId(UUID.fromString(f"00000000-0000-0000-0000-$n%012d"))
  private val u1 = user(1)
  private val u2 = user(2)
  private val u3 = user(3)
  private val kz1 = CellId("kz-1")
  private val kz2 = CellId("kz-2")
  private val kz3 = CellId("kz-3")
  private val key = DirectoryKey.derive(Secret(Array.fill(16)(7.toByte)))
  private val t0 = Instant.parse("2027-01-01T00:00:00Z")
  private def later(seconds: Long) = t0.plusSeconds(seconds)
  private val drainMoveId = UUID.fromString("00000000-0000-7000-8000-0000000000dd")
  private def eventId(n: Int) = UUID.fromString(f"00000000-0000-7000-8000-$n%012d")

  private def move(
      id: UserId,
      from: CellId = kz1,
      to: CellId = kz2,
      phase: MovePhase = MovePhase.Leaving,
      reason: MoveReason = MoveReason.Admin,
      startedAt: Instant = t0,
      notBefore: Instant = t0,
  ) =
    UserMove(
      userId = id,
      fromCell = from,
      toCell = to,
      phase = phase,
      startedAt = startedAt,
      updatedAt = startedAt,
      attempts = 0,
      error = None,
      reason = reason,
      notBefore = notBefore,
      departedAt = None,
      moveId = moveIdOf(id),
      digest = None,
    )

  /** The id a move of `id` is given in these cases: the user's, so that each case can name it. */
  private def moveIdOf(id: UserId) = UUID.fromString(id.toString.replace("-0000-0000-0000-", "-7000-8000-0000-"))

  /** The move of `id` given up and cleaned up: `Abandoning`, then `Failed`. */
  private def giveUp(env: UserMoveRepositorySpec.Env, id: UserId, from: MovePhase, error: String, at: Instant) =
    env.moves.beginAbandon(id, from, error, at, at) *> env.moves.abandoned(id, at)

  /** A user the directory places in `cell`, with the event of its creation queued for auth. */
  private def placed(env: UserMoveRepositorySpec.Env, id: UserId, cell: CellId) =
    env.users.create(
      user = UserDirectoryRecord(id, Some(cell)),
      identifiers = List(key.email(Email(s"$id@example.com"))),
      event = OutboxEvent.UpsertUser(userId = id, version = eventId(1), email = None, phone = None, login = None),
      eventId = UUID.randomUUID(),
    )

  /** The move of `id`, taken through the given phases from `Leaving`. */
  private def reach(env: UserMoveRepositorySpec.Env, id: UserId, phases: MovePhase*) =
    env.moves.start(move(id)) *> ZIO.foldLeft(phases)(MovePhase.Leaving): (from, to) =>
      (if to == MovePhase.Arrived then env.moves.arrived(id, "digest-1", t0, t0) else env.moves.advance(id, from, to, t0, t0)).as(to)

  override def testCases(env: UserMoveRepositorySpec.Env) =
    List(
      test("a started move is stored as it was given, and there is one row per user") {
        for
          started <- env.moves.start(move(u1))
          found <- env.moves.find(u1)
          other <- env.moves.find(u2)
          unfinished <- env.moves.unfinished(u1)
          unfinishedOther <- env.moves.unfinished(u2)
        yield assertTrue(started, found.contains(move(u1)), other.isEmpty, unfinished, !unfinishedOther)
      },
      test("a move of a user whose move is unfinished is not started, and the move is left as it was") {
        for
          _ <- env.moves.start(move(u1))
          _ <- env.moves.advance(u1, MovePhase.Leaving, MovePhase.Exported, later(1), later(1))
          again <- env.moves.start(move(u1, to = kz3, startedAt = later(5)))
          found <- env.moves.find(u1)
        yield assertTrue(!again, found.map(_.toCell).contains(kz2), found.map(_.phase).contains(MovePhase.Exported))
      },
      test("a finished or abandoned move is replaced by the next one of the user") {
        for
          _ <- placed(env, u1, kz1)
          _ <- reach(env, u1, MovePhase.Exported, MovePhase.Arrived)
          _ <- env.moves.flip(u1, later(1), later(1))
          _ <- env.moves.advance(u1, MovePhase.Flipped, MovePhase.Done, later(2), later(2))
          afterDone <- env.moves.start(move(u1, from = kz2, to = kz1, startedAt = later(10)))
          second <- env.moves.find(u1)
          _ <- giveUp(env, u1, MovePhase.Leaving, MoveError.DepartFailed, later(11))
          afterFailed <- env.moves.start(move(u1, from = kz2, to = kz3, startedAt = later(20)))
          third <- env.moves.find(u1)
        yield assertTrue(
          afterDone,
          second.map(m => (m.fromCell, m.toCell, m.phase)).contains((kz2, kz1, MovePhase.Leaving)),
          afterFailed,
          third.map(m => (m.toCell, m.startedAt, m.error)).contains((kz3, later(20), None)),
        )
      },
      test("a phase changes only from the phase the caller saw, and a change clears the attempts and the error") {
        for
          _ <- env.moves.start(move(u1))
          _ <- env.moves.retry(u1, MovePhase.Leaving, attempts = 2, error = MoveError.ExportFailed, now = later(1), notBefore = later(9))
          wrong <- env.moves.advance(u1, MovePhase.Exported, MovePhase.Arrived, later(2), later(2))
          afterWrong <- env.moves.find(u1)
          right <- env.moves.advance(u1, MovePhase.Leaving, MovePhase.Exported, later(3), later(3))
          afterRight <- env.moves.find(u1)
        yield assertTrue(
          !wrong,
          afterWrong.map(m => (m.phase, m.attempts, m.error)).contains((MovePhase.Leaving, 2, Some(MoveError.ExportFailed))),
          right,
          afterRight.map(m => (m.phase, m.attempts, m.error, m.updatedAt, m.notBefore)).contains((MovePhase.Exported, 0, None, later(3), later(3))),
        )
      },
      test("a failure is counted against the phase it was at: a move that has moved on is not touched") {
        for
          _ <- reach(env, u1, MovePhase.Exported)
          stale <- env.moves.retry(u1, MovePhase.Leaving, attempts = 1, error = MoveError.DepartFailed, now = later(1), notBefore = later(2))
          current <- env.moves.retry(u1, MovePhase.Exported, attempts = 1, error = MoveError.ArriveFailed, now = later(1), notBefore = later(30))
          found <- env.moves.find(u1)
        yield assertTrue(
          !stale,
          current,
          found.map(m => (m.attempts, m.error, m.notBefore)).contains((1, Some(MoveError.ArriveFailed), later(30))),
        )
      },
      test("abandoning is two guarded writes: to abandoning, only from the phase the caller saw, then to failed, keeping the error") {
        for
          _ <- env.moves.start(move(u1))
          wrong <- env.moves.beginAbandon(u1, MovePhase.Arrived, MoveError.UserGone, later(1), later(1))
          afterWrong <- env.moves.find(u1)
          right <- env.moves.beginAbandon(u1, MovePhase.Leaving, MoveError.ArriveFailed, later(2), later(5))
          abandoning <- env.moves.find(u1)
          stillUnfinished <- env.moves.unfinished(u1)
          twice <- env.moves.beginAbandon(u1, MovePhase.Leaving, MoveError.UserGone, later(3), later(3))
          finished <- env.moves.abandoned(u1, later(6))
          failed <- env.moves.find(u1)
          unfinished <- env.moves.unfinished(u1)
          again <- env.moves.abandoned(u1, later(7))
        yield assertTrue(
          !wrong,
          afterWrong.map(_.phase).contains(MovePhase.Leaving),
          right,
          abandoning.map(m => (m.phase, m.error, m.updatedAt, m.notBefore)).contains((
            MovePhase.Abandoning,
            Some(MoveError.ArriveFailed),
            later(2),
            later(5),
          )),
          // an abandoning move is not over: the user is still held back, and its events wait
          stillUnfinished,
          // the second reach for the same move changes nothing, and does not replace the error
          !twice,
          finished,
          failed.map(m => (m.phase, m.error, m.updatedAt)).contains((MovePhase.Failed, Some(MoveError.ArriveFailed), later(6))),
          !unfinished,
          !again,
        )
      },
      test("a move that is flipped, done, failed or abandoning is never given up: the flip is not rolled back") {
        for
          _ <- placed(env, u1, kz1)
          _ <- reach(env, u1, MovePhase.Exported, MovePhase.Arrived)
          _ <- env.moves.flip(u1, later(1), later(1))
          flipped <- env.moves.beginAbandon(u1, MovePhase.Flipped, MoveError.UserGone, later(2), later(2))
          _ <- env.moves.advance(u1, MovePhase.Flipped, MovePhase.Done, later(3), later(3))
          done <- env.moves.beginAbandon(u1, MovePhase.Done, MoveError.UserGone, later(4), later(4))
          found <- env.moves.find(u1)
        yield assertTrue(!flipped, !done, found.map(_.phase).contains(MovePhase.Done))
      },
      test("of two instances, one that flips and one that abandons, exactly one wins, and the loser changes nothing") {
        for
          _ <- placed(env, u1, kz1)
          _ <- reach(env, u1, MovePhase.Exported, MovePhase.Arrived)
          // abandoning first: the flip finds the move gone
          abandonedFirst <- env.moves.beginAbandon(u1, MovePhase.Arrived, MoveError.UserGone, later(1), later(1))
          flipLost <- env.moves.flip(u1, later(2), later(2))
          directory <- env.users.find(u1)
          _ <- placed(env, u2, kz1)
          _ <- reach(env, u2, MovePhase.Exported, MovePhase.Arrived)
          // flipping first: the abandon finds the move gone
          flipWon <- env.moves.flip(u2, later(1), later(1))
          abandonLost <- env.moves.beginAbandon(u2, MovePhase.Arrived, MoveError.UserGone, later(2), later(2))
          flippedMove <- env.moves.find(u2)
        yield assertTrue(
          abandonedFirst,
          flipLost == FlipResult.NotArrived,
          directory.contains(UserDirectoryRecord(u1, Some(kz1))),
          flipWon == FlipResult.Flipped,
          !abandonLost,
          flippedMove.map(_.phase).contains(MovePhase.Flipped),
        )
      },
      test("arriving stores the digest of what was delivered and moves on from exported only; delivering again replaces it at arrived only") {
        for
          _ <- env.moves.start(move(u1))
          early <- env.moves.arrived(u1, "digest-1", later(1), later(1))
          earlyRedelivery <- env.moves.redelivered(u1, "digest-x", later(1))
          _ <- env.moves.advance(u1, MovePhase.Leaving, MovePhase.Exported, later(2), later(2))
          _ <- env.moves.retry(u1, MovePhase.Exported, attempts = 3, error = MoveError.ArriveFailed, now = later(3), notBefore = later(3))
          arrived <- env.moves.arrived(u1, "digest-1", later(4), later(5))
          found <- env.moves.find(u1)
          redelivered <- env.moves.redelivered(u1, "digest-2", later(6))
          afterRedelivery <- env.moves.find(u1)
          again <- env.moves.arrived(u1, "digest-3", later(7), later(7))
        yield assertTrue(
          !early,
          !earlyRedelivery,
          arrived,
          found.map(m => (m.phase, m.digest, m.attempts, m.error, m.notBefore)).contains((MovePhase.Arrived, Some("digest-1"), 0, None, later(5))),
          redelivered,
          afterRedelivery.map(m => (m.phase, m.digest, m.updatedAt)).contains((MovePhase.Arrived, Some("digest-2"), later(6))),
          !again,
        )
      },
      test("an abandoning move is claimed like any unfinished one, and counted by phase and by cell") {
        for
          _ <- env.moves.start(move(u1))
          _ <- env.moves.beginAbandon(u1, MovePhase.Leaving, MoveError.DepartFailed, later(1), later(2))
          early <- env.moves.claimDue(later(1), limit = 10, lease = Duration.fromSeconds(60))
          due <- env.moves.claimDue(later(2), limit = 10, lease = Duration.fromSeconds(60))
          byPhase <- env.moves.countUnfinishedByPhase
          fromCells <- env.moves.unfinishedFromCounts
          involved <- env.moves.involvesUnfinished(kz2)
        yield assertTrue(
          early.isEmpty,
          due.map(m => (m.userId, m.phase)) == Vector((u1, MovePhase.Abandoning)),
          byPhase == Map(MovePhase.Abandoning -> 1L),
          fromCells == Map(kz1 -> 1L),
          involved,
        )
      },
      test("every move has its own id, kept with the row, and the next move of the user has another") {
        val next = UUID.fromString("00000000-0000-7000-8000-0000000000aa")
        for
          _ <- env.moves.start(move(u1))
          first <- env.moves.find(u1)
          _ <- giveUp(env, u1, MovePhase.Leaving, MoveError.DepartFailed, later(1))
          _ <- env.moves.start(move(u1, startedAt = later(2)).copy(moveId = next))
          second <- env.moves.find(u1)
        yield assertTrue(first.map(_.moveId).contains(moveIdOf(u1)), second.map(m => (m.moveId, m.digest)).contains((next, None)))
      },
      test("the source cell's acknowledgement is stored once, and the move waits the settle time") {
        for
          _ <- env.moves.start(move(u1))
          marked <- env.moves.markDeparted(u1, later(1), notBefore = later(3))
          found <- env.moves.find(u1)
          _ <- env.moves.advance(u1, MovePhase.Leaving, MovePhase.Exported, later(4), later(4))
          late <- env.moves.markDeparted(u1, later(5), notBefore = later(7))
        yield assertTrue(marked, found.map(m => (m.departedAt, m.notBefore, m.phase)).contains((Some(later(1)), later(3), MovePhase.Leaving)), !late)
      },
      test("the flip gives the directory row to the target and moves the move to flipped, together") {
        for
          _ <- placed(env, u1, kz1)
          _ <- reach(env, u1, MovePhase.Exported, MovePhase.Arrived)
          result <- env.moves.flip(u1, later(1), later(1))
          directory <- env.users.find(u1)
          found <- env.moves.find(u1)
        yield assertTrue(
          result == FlipResult.Flipped,
          directory.contains(UserDirectoryRecord(u1, Some(kz2))),
          found.map(_.phase).contains(MovePhase.Flipped),
        )
      },
      test("the flip is refused when the directory names another cell than the source, and neither the row nor the move changes") {
        for
          _ <- placed(env, u1, kz3)
          _ <- reach(env, u1, MovePhase.Exported, MovePhase.Arrived)
          result <- env.moves.flip(u1, later(1), later(1))
          directory <- env.users.find(u1)
          found <- env.moves.find(u1)
        yield assertTrue(
          result == FlipResult.NotInSource,
          directory.contains(UserDirectoryRecord(u1, Some(kz3))),
          found.map(_.phase).contains(MovePhase.Arrived),
        )
      },
      test("the flip is refused when the user is no longer in the directory") {
        for
          _ <- placed(env, u1, kz1)
          _ <- reach(env, u1, MovePhase.Exported, MovePhase.Arrived)
          _ <- env.users.delete(u1)
          result <- env.moves.flip(u1, later(1), later(1))
          directory <- env.users.find(u1)
          found <- env.moves.find(u1)
        yield assertTrue(result == FlipResult.NotInSource, directory.isEmpty, found.map(_.phase).contains(MovePhase.Arrived))
      },
      test("the flip happens once: a move that is not arrived is not flipped, and a flipped one is not flipped again") {
        for
          _ <- placed(env, u1, kz1)
          _ <- env.moves.start(move(u1))
          early <- env.moves.flip(u1, later(1), later(1))
          directoryEarly <- env.users.find(u1)
          _ <- reach(env, u2, MovePhase.Exported, MovePhase.Arrived)
          _ <- placed(env, u2, kz1)
          first <- env.moves.flip(u2, later(2), later(2))
          second <- env.moves.flip(u2, later(3), later(3))
          none <- env.moves.flip(u3, later(3), later(3))
        yield assertTrue(
          early == FlipResult.NotArrived,
          directoryEarly.contains(UserDirectoryRecord(u1, Some(kz1))),
          first == FlipResult.Flipped,
          second == FlipResult.NotArrived,
          none == FlipResult.NotArrived,
        )
      },
      test("only the unfinished moves that are due are claimed, oldest first, and each is leased") {
        for
          _ <- env.moves.start(move(u1, startedAt = t0))
          _ <- env.moves.start(move(u2, startedAt = later(1), notBefore = later(100)))
          _ <- env.moves.start(move(u3, startedAt = later(2)))
          _ <- giveUp(env, u3, MovePhase.Leaving, MoveError.DepartFailed, later(3))
          claimed <- env.moves.claimDue(later(10), limit = 10, lease = Duration.fromSeconds(60))
          leasedAgain <- env.moves.claimDue(later(20), limit = 10, lease = Duration.fromSeconds(60))
          afterLease <- env.moves.claimDue(later(71), limit = 10, lease = Duration.fromSeconds(60))
          afterNotBefore <- env.moves.claimDue(later(101), limit = 10, lease = Duration.fromSeconds(60))
        yield assertTrue(
          claimed.map(_.userId) == Vector(u1),
          // the lease is taken until `now + lease`, so another instance finds nothing, and takes it over once it ran out
          leasedAgain.isEmpty,
          afterLease.map(_.userId) == Vector(u1),
          afterNotBefore.map(_.userId) == Vector(u2),
        )
      },
      test("a move is claimed at most once at a time: instances that claim together are given disjoint moves") {
        val users = (1 to 12).map(user)
        for
          _ <- ZIO.foreachDiscard(users)(id => env.moves.start(move(id)))
          claims <- ZIO.foreachPar(1 to 4)(_ => env.moves.claimDue(later(1), limit = 12, lease = Duration.fromSeconds(60)))
          ids = claims.flatten.map(_.userId)
        yield assertTrue(ids.size == 12, ids.distinct.size == 12)
      },
      test("the limit of a claim is kept") {
        for
          _ <- ZIO.foreachDiscard(1 to 5)(n => env.moves.start(move(user(n), startedAt = later(n))))
          claimed <- env.moves.claimDue(later(10), limit = 2, lease = Duration.fromSeconds(60))
        yield assertTrue(claimed.map(_.userId) == Vector(user(1), user(2)))
      },
      test("moves are listed newest first, of a cell (from it or to it), of a phase, up to the limit") {
        for
          _ <- env.moves.start(move(u1, from = kz1, to = kz2, startedAt = t0))
          _ <- env.moves.start(move(u2, from = kz2, to = kz3, startedAt = later(1)))
          _ <- env.moves.start(move(u3, from = kz3, to = kz1, startedAt = later(2)))
          _ <- giveUp(env, u2, MovePhase.Leaving, MoveError.DepartFailed, later(3))
          all <- env.moves.list(None, None, 10)
          ofKz2 <- env.moves.list(Some(kz2), None, 10)
          failed <- env.moves.list(None, Some(MovePhase.Failed), 10)
          failedOfKz1 <- env.moves.list(Some(kz1), Some(MovePhase.Failed), 10)
          limited <- env.moves.list(None, None, 2)
        yield assertTrue(
          all.map(_.userId) == Vector(u3, u2, u1),
          ofKz2.map(_.userId) == Vector(u2, u1),
          failed.map(_.userId) == Vector(u2),
          failedOfKz1.isEmpty,
          limited.map(_.userId) == Vector(u3, u2),
        )
      },
      test("the unfinished moves are counted by phase, by the cell they leave, and by whether a cell is part of one") {
        for
          _ <- env.moves.start(move(u1, from = kz1, to = kz2))
          _ <- env.moves.start(move(u2, from = kz1, to = kz3))
          _ <- env.moves.start(move(u3, from = kz2, to = kz3))
          _ <- env.moves.advance(u2, MovePhase.Leaving, MovePhase.Exported, t0, t0)
          _ <- giveUp(env, u3, MovePhase.Leaving, MoveError.DepartFailed, t0)
          byPhase <- env.moves.countUnfinishedByPhase
          fromCells <- env.moves.unfinishedFromCounts
          kz1Involved <- env.moves.involvesUnfinished(kz1)
          kz2Involved <- env.moves.involvesUnfinished(kz2)
          kz3Involved <- env.moves.involvesUnfinished(kz3)
          kz9Involved <- env.moves.involvesUnfinished(CellId("kz-9"))
        yield assertTrue(
          byPhase == Map(MovePhase.Leaving -> 1L, MovePhase.Exported -> 1L),
          fromCells == Map(kz1 -> 2L),
          kz1Involved,
          kz2Involved,
          // the move to kz-3 that is unfinished is u2's; the abandoned one of u3 is not
          kz3Involved,
          !kz9Involved,
        )
      },
      test("a move that is over, one way or the other, does not make a cell a part of an unfinished move") {
        for
          _ <- env.moves.start(move(u1, from = kz1, to = kz2))
          _ <- giveUp(env, u1, MovePhase.Leaving, MoveError.DepartFailed, t0)
          involved <- env.moves.involvesUnfinished(kz2)
        yield assertTrue(!involved)
      },
      test("the events of a user with an unfinished move wait, and are claimed once the move is done or abandoned") {
        for
          _ <- placed(env, u1, kz1)
          _ <- placed(env, u2, kz1)
          _ <- env.moves.start(move(u1))
          whileMoving <- env.users.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- env.users.rescheduleEvent(whileMoving.head.id, Duration.fromSeconds(0))
          _ <- reach(env, u1, MovePhase.Exported, MovePhase.Arrived)
          _ <- env.moves.flip(u1, t0, t0)
          flipped <- env.users.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- env.moves.advance(u1, MovePhase.Flipped, MovePhase.Done, t0, t0)
          afterDone <- env.users.claimDueEvents(10, Duration.fromSeconds(0))
        yield assertTrue(
          // the user of no move is served meanwhile
          whileMoving.map(_.userId) == Vector(u2),
          flipped.map(_.userId) == Vector(u2),
          afterDone.map(_.userId).contains(u1),
        )
      },
      test("a pending event stays queued in order behind a move that was abandoned, and is then claimed") {
        for
          _ <- placed(env, u1, kz1)
          _ <- env.moves.start(move(u1))
          held <- env.users.claimDueEvents(10, Duration.fromSeconds(0))
          _ <- giveUp(env, u1, MovePhase.Leaving, MoveError.DepartFailed, t0)
          released <- env.users.claimDueEvents(10, Duration.fromSeconds(0))
        yield assertTrue(held.isEmpty, released.map(_.userId) == Vector(u1))
      },
      suite("the drain of a cell")(
        test("a user of the cell that has no move is taken, to the target given, with the reason drain") {
          for
            _ <- placed(env, u1, kz1)
            _ <- placed(env, u2, kz2)
            taken <- env.moves.startDrain(kz1, kz3, drainMoveId, cap = 4, now = later(1), retryBefore = t0)
            found <- env.moves.find(u1)
            other <- env.moves.find(u2)
            nobody <- env.moves.startDrain(kz1, kz3, drainMoveId, cap = 4, now = later(2), retryBefore = t0)
          yield assertTrue(
            taken.contains(u1),
            found.map(m => (m.fromCell, m.toCell, m.reason, m.phase, m.startedAt, m.notBefore, m.moveId, m.digest)).contains(
              (kz1, kz3, MoveReason.Drain, MovePhase.Leaving, later(1), later(1), drainMoveId, None),
            ),
            // only the cell's own users; the one move of the user is in progress
            other.isEmpty,
            nobody.isEmpty,
          )
        },
        test("at most cap moves with the reason drain leave the cell at a time, and a finished one makes room") {
          for
            _ <- ZIO.foreachDiscard(1 to 5)(n => placed(env, user(n), kz1))
            first <- ZIO.foreach(1 to 4)(_ => env.moves.startDrain(kz1, kz2, drainMoveId, cap = 2, now = later(1), retryBefore = t0))
            _ <- giveUp(env, first.flatten.head, MovePhase.Leaving, MoveError.DepartFailed, later(2))
            afterOne <- env.moves.startDrain(kz1, kz2, drainMoveId, cap = 2, now = later(3), retryBefore = t0)
          yield assertTrue(first.flatten.size == 2, afterOne.isDefined)
        },
        test("moves of an administrator are not counted against the pacing, nor are moves out of another cell") {
          for
            _ <- ZIO.foreachDiscard(1 to 4)(n => placed(env, user(n), kz1))
            _ <- env.moves.start(move(user(10), from = kz1, reason = MoveReason.Admin))
            _ <- env.moves.start(move(user(11), from = kz2, reason = MoveReason.Drain))
            started <- ZIO.foreach(1 to 4)(_ => env.moves.startDrain(kz1, kz3, drainMoveId, cap = 2, now = later(1), retryBefore = t0))
          yield assertTrue(started.flatten.size == 2)
        },
        test("instances that drain together keep to the cap") {
          for
            _ <- ZIO.foreachDiscard(1 to 12)(n => placed(env, user(n), kz1))
            started <- ZIO.foreachPar(1 to 8)(_ => env.moves.startDrain(kz1, kz2, drainMoveId, cap = 3, now = later(1), retryBefore = t0))
            unfinished <- env.moves.unfinishedFromCounts
          yield assertTrue(started.flatten.size == 3, started.flatten.distinct.size == 3, unfinished == Map(kz1 -> 3L))
        },
        test("a user whose last move is done is taken again, and one whose move failed only after the retry time") {
          for
            _ <- placed(env, u1, kz1)
            _ <- placed(env, u2, kz1)
            _ <- reach(env, u1, MovePhase.Exported, MovePhase.Arrived)
            _ <- env.moves.flip(u1, later(1), later(1))
            _ <- env.moves.advance(u1, MovePhase.Flipped, MovePhase.Done, later(2), later(2))
            // u1 is in kz-2 now; the directory is put back so that it is in the cell again
            _ <- env.users.delete(u1)
            _ <- placed(env, u1, kz1)
            _ <- env.moves.start(move(u2))
            _ <- giveUp(env, u2, MovePhase.Leaving, MoveError.ArriveFailed, later(100))
            // u2 failed at 100: not tried before 100 + retry; u1 is done and taken at once
            early <- env.moves.startDrain(kz1, kz3, drainMoveId, cap = 4, now = later(101), retryBefore = later(101 - 600))
            tooEarly <- env.moves.startDrain(kz1, kz3, drainMoveId, cap = 4, now = later(102), retryBefore = later(102 - 600))
            retryTime <- env.moves.startDrain(kz1, kz3, drainMoveId, cap = 4, now = later(701), retryBefore = later(101))
            retried <- env.moves.find(u2)
          yield assertTrue(
            early.contains(u1),
            tooEarly.isEmpty,
            retryTime.contains(u2),
            retried.map(m => (m.phase, m.reason, m.attempts, m.error, m.toCell)).contains((MovePhase.Leaving, MoveReason.Drain, 0, None, kz3)),
          )
        },
        test("a user with an unfinished move of an administrator is left to it") {
          for
            _ <- placed(env, u1, kz1)
            _ <- env.moves.start(move(u1, to = kz3))
            taken <- env.moves.startDrain(kz1, kz2, drainMoveId, cap = 4, now = later(1), retryBefore = t0)
            found <- env.moves.find(u1)
          yield assertTrue(taken.isEmpty, found.map(m => (m.toCell, m.reason)).contains((kz3, MoveReason.Admin)))
        },
      ),
    )

object UserMoveRepositorySpec:
  case class Env(moves: UserMoveRepository, users: UserRepository)

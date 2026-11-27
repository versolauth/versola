package versola.central.users

import versola.central.configuration.topology.CellId
import zio.{Duration, Ref, Task, UIO, ZIO}

import java.time.Instant
import java.util.UUID

/** The moves and the directory rows they flip, in memory, with the behaviour [[UserMoveRepositorySpec]] holds a storage to, and a way
  * to make the process die between two writes: the one that is the `crashAt`th is not applied (or is, and the answer is lost), and
  * every write and claim after it fails until [[revive]]. Not a stub: the processor's own state machine is under test, and it reads
  * back what it wrote.
  */
final class InMemoryUserMoves private (state: Ref[InMemoryUserMoves.State], faults: Ref[InMemoryUserMoves.Faults]) extends UserMoveRepository:
  import InMemoryUserMoves.*

  /** The process dies at the `n`th write from now (counted from 1 over the calls that change something). `applied`: the write took
    * effect and only the answer was lost. */
  def crashAt(n: Int, applied: Boolean): UIO[Unit] = faults.update(f => f.copy(crash = Some(Crash(f.writes + n, applied))))

  /** The process is started again: what was stored is read back, and nothing fails. */
  val revive: UIO[Unit] = faults.update(_.copy(crash = None, dead = false))

  def place(user: UserId, cell: Option[CellId]): UIO[Unit] = state.update(s => s.copy(directory = s.directory.updated(user, cell)))

  def unplace(user: UserId): UIO[Unit] = state.update(s => s.copy(directory = s.directory - user))

  def cellOf(user: UserId): UIO[Option[CellId]] = state.get.map(_.directory.get(user).flatten)

  def writes: UIO[Int] = faults.get.map(_.writes)

  private def write[A](change: State => (A, State)): Task[A] =
    faults.modify: f =>
      if f.dead then (Decision.Dead, f)
      else
        val n = f.writes + 1
        f.crash match
          case Some(Crash(`n`, applied)) => (if applied then Decision.ApplyThenDie else Decision.DieFirst, f.copy(writes = n, dead = true))
          case _ => (Decision.Apply, f.copy(writes = n))
    .flatMap:
      case Decision.Apply => state.modify(change)
      case Decision.ApplyThenDie => state.modify(change) *> ZIO.fail(Crashed)
      case Decision.DieFirst | Decision.Dead => ZIO.fail(Crashed)

  private def put(s: State, move: UserMove) = s.copy(moves = s.moves.updated(move.userId, move))

  override def find(userId: UserId): Task[Option[UserMove]] = state.get.map(_.moves.get(userId))

  override def unfinished(userId: UserId): Task[Boolean] = state.get.map(_.moves.get(userId).exists(_.phase.unfinished))

  override def start(move: UserMove): Task[Boolean] =
    write: s =>
      if s.moves.get(move.userId).exists(_.phase.unfinished) then (false, s) else (true, put(s, move))

  override def claimDue(now: Instant, limit: Int, lease: Duration): Task[Vector[UserMove]] =
    faults.get.flatMap(f => ZIO.fail(Crashed).when(f.dead)) *>
      state.modify: s =>
        val due = s.moves.values.filter(m => m.phase.unfinished && !m.notBefore.isAfter(now)).toVector
          .sortBy(m => (m.startedAt, m.userId.toString)).take(limit).map(_.copy(notBefore = now.plus(lease)))
        (due, due.foldLeft(s)(put))

  private def change(userId: UserId, phase: MovePhase)(update: UserMove => UserMove): Task[Boolean] =
    write: s =>
      s.moves.get(userId).filter(_.phase == phase) match
        case Some(m) => (true, put(s, update(m)))
        case None => (false, s)

  override def markDeparted(userId: UserId, now: Instant, notBefore: Instant): Task[Boolean] =
    change(userId, MovePhase.Leaving)(_.copy(departedAt = Some(now), updatedAt = now, notBefore = notBefore))

  override def advance(userId: UserId, from: MovePhase, to: MovePhase, now: Instant, notBefore: Instant): Task[Boolean] =
    change(userId, from)(_.copy(phase = to, updatedAt = now, attempts = 0, error = None, notBefore = notBefore))

  override def retry(userId: UserId, phase: MovePhase, attempts: Int, error: String, now: Instant, notBefore: Instant): Task[Boolean] =
    change(userId, phase)(_.copy(attempts = attempts, error = Some(error), updatedAt = now, notBefore = notBefore))

  override def arrived(userId: UserId, digest: String, now: Instant, notBefore: Instant): Task[Boolean] =
    change(userId, MovePhase.Exported)(
      _.copy(phase = MovePhase.Arrived, digest = Some(digest), updatedAt = now, attempts = 0, error = None, notBefore = notBefore),
    )

  override def redelivered(userId: UserId, digest: String, now: Instant): Task[Boolean] =
    change(userId, MovePhase.Arrived)(_.copy(digest = Some(digest), updatedAt = now))

  override def beginAbandon(userId: UserId, from: MovePhase, error: String, now: Instant, notBefore: Instant): Task[Boolean] =
    if from == MovePhase.Leaving || from == MovePhase.Exported || from == MovePhase.Arrived then
      change(userId, from)(_.copy(phase = MovePhase.Abandoning, error = Some(error), updatedAt = now, notBefore = notBefore))
    else ZIO.succeed(false)

  override def abandoned(userId: UserId, now: Instant): Task[Boolean] =
    change(userId, MovePhase.Abandoning)(_.copy(phase = MovePhase.Failed, updatedAt = now))

  override def flip(userId: UserId, now: Instant, notBefore: Instant): Task[FlipResult] =
    write: s =>
      s.moves.get(userId).filter(_.phase == MovePhase.Arrived) match
        case None => (FlipResult.NotArrived, s)
        case Some(m) if !s.directory.get(userId).flatten.contains(m.fromCell) => (FlipResult.NotInSource, s)
        case Some(m) =>
          val flipped = m.copy(phase = MovePhase.Flipped, updatedAt = now, attempts = 0, error = None, notBefore = notBefore)
          (FlipResult.Flipped, put(s, flipped).copy(directory = s.directory.updated(userId, Some(m.toCell))))

  override def list(cell: Option[CellId], phase: Option[MovePhase], limit: Int): Task[Vector[UserMove]] =
    state.get.map(
      _.moves.values.toVector
        .filter(m => cell.forall(c => m.fromCell == c || m.toCell == c) && phase.forall(_ == m.phase))
        .sortBy(m => (m.startedAt, m.userId.toString))(using Ordering[(Instant, String)].reverse).take(limit),
    )

  override def countUnfinishedByPhase: Task[Map[MovePhase, Long]] =
    state.get.map(_.moves.values.filter(_.phase.unfinished).groupBy(_.phase).map((phase, moves) => phase -> moves.size.toLong))

  override def unfinishedFromCounts: Task[Map[CellId, Long]] =
    state.get.map(_.moves.values.filter(_.phase.unfinished).groupBy(_.fromCell).map((cell, moves) => cell -> moves.size.toLong))

  override def involvesUnfinished(cell: CellId): Task[Boolean] =
    state.get.map(_.moves.values.exists(m => m.phase.unfinished && (m.fromCell == cell || m.toCell == cell)))

  override def startDrain(from: CellId, to: CellId, moveId: UUID, cap: Int, now: Instant, retryBefore: Instant): Task[Option[UserId]] =
    ZIO.dieMessage("the drain is not under test with this repository")

object InMemoryUserMoves:
  private case class State(moves: Map[UserId, UserMove], directory: Map[UserId, Option[CellId]])
  private case class Crash(at: Int, applied: Boolean)
  private case class Faults(writes: Int, crash: Option[Crash], dead: Boolean)

  private enum Decision:
    case Apply, ApplyThenDie, DieFirst, Dead

  /** The process died. */
  case object Crashed extends RuntimeException("the process died")

  def make: UIO[InMemoryUserMoves] =
    for
      state <- Ref.make(State(Map.empty, Map.empty))
      faults <- Ref.make(Faults(writes = 0, crash = None, dead = false))
    yield InMemoryUserMoves(state, faults)

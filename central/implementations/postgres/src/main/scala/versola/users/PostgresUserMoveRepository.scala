package versola.users

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.topology.CellId
import versola.central.users.{FlipResult, MovePhase, MoveReason, UserId, UserMove, UserMoveRepository}
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresUserMoveRepository(xa: TransactorZIO) extends UserMoveRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[CellId] = DbCodec.StringCodec.biMap(CellId(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[MovePhase] =
    DbCodec.StringCodec.biMap(code => MovePhase.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[MoveReason] =
    DbCodec.StringCodec.biMap(code => MoveReason.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[UserMove] = DbCodec.derived

  private case class Ends(fromCell: CellId, toCell: CellId) derives DbCodec
  private case class PhaseCount(phase: MovePhase, total: Long) derives DbCodec
  private case class CellCount(cell: CellId, total: Long) derives DbCodec

  override def find(userId: UserId): Task[Option[UserMove]] =
    xa.connectMeasured("find-user-move"):
      sql"""SELECT user_id, from_cell, to_cell, phase, started_at, updated_at, attempts, error, reason, not_before, departed_at, move_id, digest
            FROM user_moves WHERE user_id = $userId""".query[UserMove].run().headOption

  override def unfinished(userId: UserId): Task[Boolean] =
    xa.connectMeasured("user-move-unfinished"):
      sql"""SELECT COUNT(*) FROM user_moves
            WHERE user_id = $userId AND phase NOT IN ('done', 'failed')""".query[Long].run().head > 0

  /** The row of a new move in place of a finished or abandoned one; nothing when the move of the user is unfinished. */
  private def upsert(move: UserMove)(using DbCon): Boolean =
    sql"""INSERT INTO user_moves (user_id, from_cell, to_cell, phase, started_at, updated_at, attempts, error, reason, not_before,
                                  departed_at, move_id, digest)
          VALUES (${move.userId}, ${move.fromCell}, ${move.toCell}, ${move.phase}, ${move.startedAt}, ${move.updatedAt}, ${move.attempts},
                  ${move.error}, ${move.reason}, ${move.notBefore}, ${move.departedAt}, ${move.moveId}, ${move.digest})
          ON CONFLICT (user_id) DO UPDATE SET
            from_cell = EXCLUDED.from_cell, to_cell = EXCLUDED.to_cell, phase = EXCLUDED.phase, started_at = EXCLUDED.started_at,
            updated_at = EXCLUDED.updated_at, attempts = EXCLUDED.attempts, error = EXCLUDED.error, reason = EXCLUDED.reason,
            not_before = EXCLUDED.not_before, departed_at = EXCLUDED.departed_at, move_id = EXCLUDED.move_id, digest = EXCLUDED.digest
          WHERE user_moves.phase IN ('done', 'failed')""".update.run() > 0

  override def start(move: UserMove): Task[Boolean] =
    xa.connectMeasured("start-user-move"):
      upsert(move)

  override def claimDue(now: Instant, limit: Int, lease: Duration): Task[Vector[UserMove]] =
    val leasedUntil = now.plus(lease)
    xa.connectMeasured("claim-due-user-moves"):
      sql"""UPDATE user_moves SET not_before = $leasedUntil
            WHERE user_id IN (
              SELECT user_id FROM user_moves
              WHERE phase NOT IN ('done', 'failed') AND not_before <= $now
              ORDER BY started_at, user_id
              LIMIT $limit
              FOR UPDATE SKIP LOCKED
            )
            RETURNING user_id, from_cell, to_cell, phase, started_at, updated_at, attempts, error, reason, not_before, departed_at, move_id, digest"""
        .returning[UserMove].run()

  override def markDeparted(userId: UserId, now: Instant, notBefore: Instant): Task[Boolean] =
    xa.connectMeasured("mark-user-move-departed"):
      sql"""UPDATE user_moves SET departed_at = $now, updated_at = $now, not_before = $notBefore
            WHERE user_id = $userId AND phase = 'leaving'""".update.run() > 0

  override def advance(userId: UserId, from: MovePhase, to: MovePhase, now: Instant, notBefore: Instant): Task[Boolean] =
    xa.connectMeasured("advance-user-move"):
      sql"""UPDATE user_moves SET phase = ${to.code}, updated_at = $now, attempts = 0, error = NULL, not_before = $notBefore
            WHERE user_id = $userId AND phase = ${from.code}""".update.run() > 0

  override def retry(userId: UserId, phase: MovePhase, attempts: Int, error: String, now: Instant, notBefore: Instant): Task[Boolean] =
    xa.connectMeasured("retry-user-move"):
      sql"""UPDATE user_moves SET attempts = $attempts, error = $error, updated_at = $now, not_before = $notBefore
            WHERE user_id = $userId AND phase = ${phase.code}""".update.run() > 0

  override def arrived(userId: UserId, digest: String, now: Instant, notBefore: Instant): Task[Boolean] =
    xa.connectMeasured("arrived-user-move"):
      sql"""UPDATE user_moves SET phase = 'arrived', digest = $digest, updated_at = $now, attempts = 0, error = NULL, not_before = $notBefore
            WHERE user_id = $userId AND phase = 'exported'""".update.run() > 0

  override def redelivered(userId: UserId, digest: String, now: Instant): Task[Boolean] =
    xa.connectMeasured("redelivered-user-move"):
      sql"""UPDATE user_moves SET digest = $digest, updated_at = $now
            WHERE user_id = $userId AND phase = 'arrived'""".update.run() > 0

  override def beginAbandon(userId: UserId, from: MovePhase, error: String, now: Instant, notBefore: Instant): Task[Boolean] =
    xa.connectMeasured("begin-abandon-user-move"):
      // the phase is named twice on purpose: what the caller saw, and the phases a move may still be given up from; a flipped
      // move is never rolled back whatever the caller believed
      sql"""UPDATE user_moves SET phase = 'abandoning', error = $error, updated_at = $now, not_before = $notBefore
            WHERE user_id = $userId AND phase = ${from.code} AND phase IN ('leaving', 'exported', 'arrived')""".update.run() > 0

  override def abandoned(userId: UserId, now: Instant): Task[Boolean] =
    xa.connectMeasured("abandoned-user-move"):
      sql"""UPDATE user_moves SET phase = 'failed', updated_at = $now
            WHERE user_id = $userId AND phase = 'abandoning'""".update.run() > 0

  override def flip(userId: UserId, now: Instant, notBefore: Instant): Task[FlipResult] =
    xa.transactMeasured("flip-user-move"):
      val ends = sql"SELECT from_cell, to_cell FROM user_moves WHERE user_id = $userId AND phase = 'arrived' FOR UPDATE"
        .query[Ends].run().headOption
      ends match
        case None => FlipResult.NotArrived
        case Some(Ends(from, to)) =>
          val named = sql"UPDATE user_directory SET cell_id = $to WHERE user_id = $userId AND cell_id = $from".update.run()
          if named == 0 then FlipResult.NotInSource
          else
            sql"""UPDATE user_moves SET phase = 'flipped', updated_at = $now, attempts = 0, error = NULL, not_before = $notBefore
                  WHERE user_id = $userId""".update.run()
            FlipResult.Flipped

  override def list(cell: Option[CellId], phase: Option[MovePhase], limit: Int): Task[Vector[UserMove]] =
    xa.connectMeasured("list-user-moves"):
      (cell, phase.map(_.code)) match
        case (None, None) =>
          sql"""SELECT user_id, from_cell, to_cell, phase, started_at, updated_at, attempts, error, reason, not_before, departed_at, move_id, digest
                FROM user_moves ORDER BY started_at DESC, user_id LIMIT $limit""".query[UserMove].run()
        case (Some(cell), None) =>
          sql"""SELECT user_id, from_cell, to_cell, phase, started_at, updated_at, attempts, error, reason, not_before, departed_at, move_id, digest
                FROM user_moves WHERE from_cell = $cell OR to_cell = $cell
                ORDER BY started_at DESC, user_id LIMIT $limit""".query[UserMove].run()
        case (None, Some(code)) =>
          sql"""SELECT user_id, from_cell, to_cell, phase, started_at, updated_at, attempts, error, reason, not_before, departed_at, move_id, digest
                FROM user_moves WHERE phase = $code ORDER BY started_at DESC, user_id LIMIT $limit""".query[UserMove].run()
        case (Some(cell), Some(code)) =>
          sql"""SELECT user_id, from_cell, to_cell, phase, started_at, updated_at, attempts, error, reason, not_before, departed_at, move_id, digest
                FROM user_moves WHERE (from_cell = $cell OR to_cell = $cell) AND phase = $code
                ORDER BY started_at DESC, user_id LIMIT $limit""".query[UserMove].run()

  override def countUnfinishedByPhase: Task[Map[MovePhase, Long]] =
    xa.connectMeasured("count-user-moves-by-phase"):
      sql"""SELECT phase, COUNT(*) FROM user_moves
            WHERE phase NOT IN ('done', 'failed') GROUP BY phase""".query[PhaseCount].run()
        .map(count => count.phase -> count.total).toMap

  override def unfinishedFromCounts: Task[Map[CellId, Long]] =
    xa.connectMeasured("count-user-moves-from-cells"):
      sql"""SELECT from_cell, COUNT(*) FROM user_moves
            WHERE phase NOT IN ('done', 'failed') GROUP BY from_cell""".query[CellCount].run()
        .map(count => count.cell -> count.total).toMap

  override def involvesUnfinished(cell: CellId): Task[Boolean] =
    xa.connectMeasured("user-moves-involve-cell"):
      sql"""SELECT COUNT(*) FROM user_moves
            WHERE phase NOT IN ('done', 'failed') AND (from_cell = $cell OR to_cell = $cell)"""
        .query[Long].run().head > 0

  override def startDrain(from: CellId, to: CellId, moveId: UUID, cap: Int, now: Instant, retryBefore: Instant): Task[Option[UserId]] =
    xa.transactMeasured("start-drain-move"):
      // One drain of a cell at a time across the instances of central, so that the count below is the count the insert adds to.
      val lockKey = s"drain:$from"
      sql"SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext($lockKey))) AS locked".query[Int].run()
      val active = sql"""SELECT COUNT(*) FROM user_moves
                         WHERE from_cell = $from AND reason = 'drain' AND phase NOT IN ('done', 'failed')"""
        .query[Long].run().head
      if active >= cap then None
      else
        val candidate = sql"""SELECT d.user_id FROM user_directory d LEFT JOIN user_moves m ON m.user_id = d.user_id
                              WHERE d.cell_id = $from
                                AND (m.user_id IS NULL OR m.phase = 'done' OR (m.phase = 'failed' AND m.updated_at <= $retryBefore))
                              ORDER BY d.user_id LIMIT 1""".query[UserId].run().headOption
        candidate.flatMap: userId =>
          val move = UserMove(
            userId = userId,
            fromCell = from,
            toCell = to,
            phase = MovePhase.Leaving,
            startedAt = now,
            updatedAt = now,
            attempts = 0,
            error = None,
            reason = MoveReason.Drain,
            notBefore = now,
            departedAt = None,
            moveId = moveId,
            digest = None,
          )
          Option.when(upsert(move))(userId)

object PostgresUserMoveRepository:
  val live: ZLayer[TransactorZIO, Nothing, UserMoveRepository] =
    ZLayer.fromFunction(PostgresUserMoveRepository(_))

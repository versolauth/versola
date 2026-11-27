package versola.central.users

import versola.central.configuration.topology.CellId
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.*

import java.time.Instant
import java.util.UUID

/** Where a move of a user between cells is (versolauth/versola#1051). A move is `leaving` while the source cell is told and the
  * user's rows are read, `exported` once they are, `arrived` once the target cell holds them, `flipped` once the directory names
  * the target (from here the move is never rolled back) and `done` once the source cell has dropped them. `abandoning` is a move
  * given up before the flip whose clean-up (the target forgets the arrival, the source serves the user again) is not finished:
  * entered by one guarded update, so that of two instances one abandons and the other finds it gone; `failed` is the abandoned
  * move once the clean-up is done.
  */
enum MovePhase(val code: String):
  case Leaving extends MovePhase("leaving")
  case Exported extends MovePhase("exported")
  case Arrived extends MovePhase("arrived")
  case Flipped extends MovePhase("flipped")
  case Done extends MovePhase("done")
  case Failed extends MovePhase("failed")
  case Abandoning extends MovePhase("abandoning")

  /** Neither finished nor abandoned for good: the user's writes are refused and the processor has work. `abandoning` counts: the
    * source may still be holding the user back. */
  def unfinished: Boolean = this != Done && this != Failed

object MovePhase:
  /** The phases the processor works on, in the order a move goes through them. */
  val unfinished: Vector[MovePhase] = values.toVector.filter(_.unfinished)

  def from(code: String): Either[String, MovePhase] =
    values.find(_.code == code).toRight(s"unknown move phase '$code', expected one of ${values.map(_.code).mkString(", ")}")

  given Schema[MovePhase] = Schema.primitive[String].transformOrFail(from, phase => Right(phase.code))
  given JsonDecoder[MovePhase] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[MovePhase] = JsonEncoder.string.contramap(_.code)

/** Who started a move: an administrator, or the drain of the cell the user was in. */
enum MoveReason(val code: String):
  case Admin extends MoveReason("admin")
  case Drain extends MoveReason("drain")

object MoveReason:
  def from(code: String): Either[String, MoveReason] =
    values.find(_.code == code).toRight(s"unknown move reason '$code', expected one of ${values.map(_.code).mkString(", ")}")

  given Schema[MoveReason] = Schema.primitive[String].transformOrFail(from, reason => Right(reason.code))
  given JsonDecoder[MoveReason] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[MoveReason] = JsonEncoder.string.contramap(_.code)

/** One row of `user_moves`.
  *
  * @param notBefore the processor leaves the move alone until then: the settle time, a backoff, or the lease of the instance
  *   working on it
  * @param departedAt when the source cell acknowledged that the user is leaving
  * @param moveId names this move at both cells: new at every start, it binds the arrival at the target to the departure at the source
  *   (an envelope of another move, or a replayed one, is refused)
  * @param digest the digest of the rows the target was last given, as the source computed it at the export; none before the first
  *   delivery. The source is asked again before the flip and a change is delivered again
  */
case class UserMove(
    userId: UserId,
    fromCell: CellId,
    toCell: CellId,
    phase: MovePhase,
    startedAt: Instant,
    updatedAt: Instant,
    attempts: Int,
    error: Option[String],
    reason: MoveReason,
    notBefore: Instant,
    departedAt: Option[Instant],
    moveId: UUID,
    digest: Option[String],
)

/** The closed set of codes a move records as its `error`: what the processor says went wrong, never what a cell answered. */
object MoveError:
  val DepartFailed = "depart_failed"

  /** The source cell answered `404` to the departure: it does not hold the user. */
  val SourceLacksUser = "source_lacks_user"
  val ExportFailed = "export_failed"
  val ArriveFailed = "arrive_failed"

  /** The target cell answered `409`: the user or one of its identifiers is already there. */
  val TargetHasUser = "target_has_user"

  /** The directory no longer names the source cell for the user: somebody deleted the user, or moved it. */
  val UserGone = "user_gone"
  val FlipFailed = "flip_failed"
  val CompleteFailed = "complete_failed"

  /** A cell of the move is not registered, or its address is not one. */
  val CellUnavailable = "cell_unavailable"

  /** The source cell answered `409 departure_exists`: another move of the user is leaving it. */
  val DepartureExists = "departure_exists"

  /** The target cell refused the envelope (`400`, `413`): the cell says it is not what the move named, or too large. */
  val ArriveRejected = "arrive_rejected"

  /** The target cell found the document does not fit its configuration (a role, client or scope it does not know): often a
    * configuration that has not reached it yet, so retried like any step. */
  val InvalidDocument = "invalid_document"

  /** The user's rows kept changing at the source while they were handed over: the last delivery was not the last change. */
  val SourceChanged = "source_changed"

  /** After the flip: the target did not confirm that it keeps the arrival. */
  val KeepFailed = "keep_failed"

  val all: Set[String] =
    Set(
      DepartFailed,
      SourceLacksUser,
      ExportFailed,
      ArriveFailed,
      TargetHasUser,
      UserGone,
      FlipFailed,
      CompleteFailed,
      CellUnavailable,
      DepartureExists,
      ArriveRejected,
      InvalidDocument,
      SourceChanged,
      KeepFailed,
    )

/** A move as the admin API shows it. */
case class UserMoveResponse(
    userId: UserId,
    moveId: UUID,
    from: CellId,
    to: CellId,
    phase: MovePhase,
    reason: MoveReason,
    startedAt: Instant,
    updatedAt: Instant,
    attempts: Int,
    error: Option[String],
) derives JsonCodec, Schema

object UserMoveResponse:
  def of(move: UserMove): UserMoveResponse =
    UserMoveResponse(
      userId = move.userId,
      moveId = move.moveId,
      from = move.fromCell,
      to = move.toCell,
      phase = move.phase,
      reason = move.reason,
      startedAt = move.startedAt,
      updatedAt = move.updatedAt,
      attempts = move.attempts,
      error = move.error,
    )

case class UserMovesResponse(moves: Vector[UserMoveResponse]) derives JsonCodec

case class StartUserMoveRequest(userId: UserId, cell: CellId) derives JsonCodec

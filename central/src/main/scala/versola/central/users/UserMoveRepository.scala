package versola.central.users

import versola.central.configuration.topology.CellId
import zio.{Duration, Task}

import java.time.Instant
import java.util.UUID

/** What central keeps of the moves of users between cells (versolauth/versola#1051). A repository stores what it is given and
  * guards each change by the phase the caller saw: whether a move is allowed, what to do next and what a failure means are the
  * processor's to decide. Every time is the caller's (`Clock`), so that a test moves it.
  */
trait UserMoveRepository:
  def find(userId: UserId): Task[Option[UserMove]]

  /** Whether the user has a move that is neither `done` nor `failed` (one that is `abandoning` is not over). */
  def unfinished(userId: UserId): Task[Boolean]

  /** Stores `move`, in place of a finished or abandoned move of the same user. `false`, and nothing stored, when a move of the
    * user is unfinished. One row per user. */
  def start(move: UserMove): Task[Boolean]

  /** The moves the processor is to work on, oldest first: unfinished, with `notBefore` not after `now`. Each is leased for
    * `lease` (its `notBefore` is moved to `now + lease`), so that another instance skips it and takes it over if this one dies.
    * The rows are returned with the lease applied. */
  def claimDue(now: Instant, limit: Int, lease: Duration): Task[Vector[UserMove]]

  /** The source cell acknowledged the departure: the settle time runs until `notBefore`. `false` when the move is no longer at
    * `Leaving`. */
  def markDeparted(userId: UserId, now: Instant, notBefore: Instant): Task[Boolean]

  /** Moves the move from `from` to `to` (not into `Flipped`, `Arrived` or `Abandoning`: those are [[flip]], [[arrived]] and
    * [[beginAbandon]]), with attempts and error cleared. `false` when the move is not at `from` any more: another instance got there
    * first, and nothing was changed. */
  def advance(userId: UserId, from: MovePhase, to: MovePhase, now: Instant, notBefore: Instant): Task[Boolean]

  /** A step of the move at `phase` failed: `attempts` are counted, `error` is its closed code, and nothing works on the move
    * before `notBefore`. `false` when the move is not at `phase` any more. */
  def retry(userId: UserId, phase: MovePhase, attempts: Int, error: String, now: Instant, notBefore: Instant): Task[Boolean]

  /** The delivery of the rows to the target succeeded: the move goes from `Exported` to `Arrived`, with the digest of what was
    * delivered, attempts and error cleared. `false` when the move is not at `Exported` any more. */
  def arrived(userId: UserId, digest: String, now: Instant, notBefore: Instant): Task[Boolean]

  /** The rows were delivered to the target again, because the source had changed them: the digest of what the target holds now.
    * `false` when the move is not at `Arrived` any more. */
  def redelivered(userId: UserId, digest: String, now: Instant): Task[Boolean]

  /** Gives up the move: from `from`, one of `Leaving`, `Exported` and `Arrived` and never `Flipped`, `Done` or `Abandoning`, to
    * `Abandoning` with `error`, due at `notBefore`. Guarded by the phase the caller saw, and by it alone: of two instances that
    * reach for the same move (one to abandon it, the other to flip it) exactly one update succeeds, and only the instance whose did
    * undoes what the move did. `false` when the move is not at `from` any more: nothing was changed. */
  def beginAbandon(userId: UserId, from: MovePhase, error: String, now: Instant, notBefore: Instant): Task[Boolean]

  /** The clean-up of an abandoned move is done: `Abandoning` to `Failed`, the error as it was set. `false` when the move is not
    * `Abandoning`. */
  def abandoned(userId: UserId, now: Instant): Task[Boolean]

  /** The flip, in one transaction: the directory row of the user is given to `move.toCell` only if it still names `move.fromCell`,
    * and the move goes from `Arrived` to `Flipped` with it. Either both change or neither does. */
  def flip(userId: UserId, now: Instant, notBefore: Instant): Task[FlipResult]

  /** The moves, newest first, of `cell` (moves from it and moves to it) and of `phase`, the ones that are not asked for left
    * unfiltered. */
  def list(cell: Option[CellId], phase: Option[MovePhase], limit: Int): Task[Vector[UserMove]]

  /** How many moves are at each of the phases that are not finished. */
  def countUnfinishedByPhase: Task[Map[MovePhase, Long]]

  /** How many unfinished moves leave each cell. */
  def unfinishedFromCounts: Task[Map[CellId, Long]]

  /** Whether a move from or to `cell` is unfinished. */
  def involvesUnfinished(cell: CellId): Task[Boolean]

  /** Starts, if the pacing and the candidates allow, a move named `moveId` with reason `Drain` of one user of `from` to `to`: at most `cap`
    * moves with that reason that leave `from` are unfinished at a time, even when several instances call this. The user is one the
    * directory places in `from` that has no move, a `done` one, or one that failed at or before `retryBefore`. `None` when no
    * move was started: `cap` is reached, or there is nobody left to take. */
  def startDrain(from: CellId, to: CellId, moveId: UUID, cap: Int, now: Instant, retryBefore: Instant): Task[Option[UserId]]

/** What [[UserMoveRepository.flip]] did. */
enum FlipResult:
  /** The directory names the target now and the move is `flipped`. */
  case Flipped

  /** The directory no longer names the source cell for the user (it was deleted or placed elsewhere): nothing changed. */
  case NotInSource

  /** The move is not at `Arrived` any more (another instance flipped it, or it was abandoned): nothing changed. */
  case NotArrived

package versola.user.move

import versola.user.model.UserId
import zio.Task

import java.time.Instant
import java.util.UUID

/** The rows that travel with a user, read as a document and written from one, the arrival that brought them, and the completion of
  * a departure. */
trait UserMoveRepository:
  /** The user's rows, with the refresh tokens that have not expired, read in one repeatable-read transaction for the move
    * `moveId` the document is made for; none when this cell does not hold the user. */
  def read(userId: UserId, moveId: UUID): Task[Option[UserMoveDocument]]

  /** Takes the document in, in one transaction, and records the arrival (`from` is the cell that sealed it):
    *
    *  - nothing of the user is here and no arrival of this move was seen: every row is inserted, the arrival is `pending`
    *    ([[UserMoveRepository.Written.Inserted]]);
    *  - the arrival of this move is `pending` and the user is here: their rows are replaced by the document's, as the purge
    *    deletes them and the insert writes them again ([[UserMoveRepository.Written.Replaced]]), which is how a move catches up
    *    with a source that changed after the export;
    *  - anything else, an arrival of this move that is `kept` or is `pending` with no user here included, a user that is here
    *    with no `pending` arrival of this move, an identifier, a passkey or a token held by another user: nothing is written
    *    ([[UserMoveRepository.Written.Taken]]). A replay of an old envelope never resurrects or overwrites anything.
    *
    * Since the user is here again, whatever said they had left (a departure, the tombstones of their tokens) is forgotten when
    * rows are inserted. */
  def write(document: UserMoveDocument, from: String, at: Instant): Task[UserMoveRepository.Written]

  /** The arrival of the move becomes `kept`; false when this cell has no arrival of that user for that move. Repeating it is
    * accepted. */
  def keep(userId: UserId, moveId: UUID, at: Instant): Task[Boolean]

  /** Removes what an abandoned move left, when the arrival of that move is `pending`: every row of the user, as a deletion
    * deletes them and with none of its side effects, and the arrival row. The cell that sealed the arrival's envelope when it did
    * something; nothing is done for any other arrival or none. */
  def discard(userId: UserId, moveId: UUID): Task[Option[String]]

  /** Removes the arrivals that are older than `olderThan` and whose user is not here; how many. */
  def removeStaleArrivals(olderThan: Instant): Task[Int]

  /** One transaction: the departure becomes `moved`, a `departed_refresh_tokens` row is written for every refresh token that has
    * not expired, and every row of the user is deleted as `DELETE /users` deletes them, without what a deletion adds. The digest
    * of the rows is recomputed in it, and compared with `digest`, the one the rows had when they were last exported. */
  def complete(userId: UserId, moveId: UUID, digest: String): Task[UserMoveRepository.Completed]

object UserMoveRepository:
  enum Written:
    /** Nothing of the user was here: the rows are in, and the arrival is `pending`. */
    case Inserted

    /** The arrival of this move was `pending`: the user's rows are now the document's. */
    case Replaced

    /** The arrival, the user, an identifier, a passkey or a token is held already as above: nothing was written. */
    case Taken

  enum Completed:
    /** Done now. `changed`: the rows were not what the digest said, so what was written after the export did not travel. */
    case Moved(toCell: String, tokens: Int, changed: Boolean)

    /** Done before: a repeat of a request whose answer was lost. Nothing was written. */
    case AlreadyMoved(toCell: String)

    /** There is no departure of that move that is `leaving`: nothing to complete. */
    case NotLeaving

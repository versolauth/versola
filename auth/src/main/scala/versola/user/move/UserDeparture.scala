package versola.user.move

import versola.user.model.UserId
import zio.json.*

import java.time.Instant
import java.util.UUID

/** Where a user's departure from this cell stands. */
enum DepartureState(val value: String):
  /** The move is in progress: this cell answers every request that would read or change the user as unavailable. */
  case Leaving extends DepartureState("leaving")

  /** The user's rows are gone from this cell; what asks for the user is told where they are. */
  case Moved extends DepartureState("moved")

object DepartureState:
  def parse(value: String): Option[DepartureState] = values.find(_.value == value)

/** A row of `user_departures`. */
final case class UserDeparture(userId: UserId, toCell: String, moveId: UUID, state: DepartureState, at: Instant)

/** What `GET /users/departures/summary` says: how many users are leaving and moved, and the refresh tokens of the moved that
  * are still answered `moved`, with the latest expiry among them (after which nothing is left to answer). */
final case class DepartureSummary(
    leaving: Long,
    moved: Long,
    tokens: Long,
    @jsonExplicitNull tokensUntil: Option[Instant],
) derives JsonCodec

/** Why a request about a user is not served here. */
enum DepartureRefusal:
  /** The user is on their way to another cell: try again in a moment. */
  case Leaving

  /** The user lives in `cell` now. */
  case Moved(cell: String)

object DepartureRefusal:
  /** How long a caller is asked to wait when the user is leaving: the move is a matter of seconds. */
  val RetryAfterSeconds = 2

/** Where an arrival from another cell stands (a row of `user_arrivals`). */
enum ArrivalState(val value: String):
  /** The rows are here, and the directory still names the cell the user came from. */
  case Pending extends ArrivalState("pending")

  /** Central flipped the directory: the user is this cell's. */
  case Kept extends ArrivalState("kept")

object ArrivalState:
  def parse(value: String): Option[ArrivalState] = values.find(_.value == value)

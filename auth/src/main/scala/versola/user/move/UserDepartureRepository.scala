package versola.user.move

import versola.oauth.client.model.ClientId
import versola.oauth.model.RefreshToken
import versola.user.model.UserId
import versola.util.MAC
import zio.Task

import java.time.Instant
import java.util.UUID

/** `user_departures` and `departed_refresh_tokens`. Which state may follow which is decided by [[UserMoves]]; this only stores
  * what it is told, and answers the one question every per-user request asks: is this user leaving. */
trait UserDepartureRepository:
  /** One read by the key of the table. */
  def find(userId: UserId): Task[Option[UserDeparture]]

  /** Marks the user `leaving` for the move `moveId` at `at`, unless there is a row for them already; whether this wrote it. */
  def begin(userId: UserId, toCell: String, moveId: UUID, at: Instant): Task[Boolean]

  /** Removes a `leaving` row; whether there was one. A `moved` row is not touched. */
  def cancel(userId: UserId): Task[Boolean]

  /** The cell a refresh token that moved with its user went to, while the token could still be used, when it was issued to
    * `clientId`: to any other client the token is not here. */
  def findToken(token: MAC.Of[RefreshToken], clientId: ClientId): Task[Option[String]]

  def summary: Task[DepartureSummary]

  /** Removes what is left of tokens that have expired; how many. */
  def removeExpiredTokens: Task[Int]

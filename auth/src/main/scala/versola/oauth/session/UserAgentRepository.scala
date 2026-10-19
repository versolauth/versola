package versola.oauth.session

import versola.oauth.model.UserAgentData
import versola.oauth.session.model.{UserAgentDetails, UserAgentId, UserAgentRecord}
import versola.user.model.UserId
import zio.{Duration, Task}

trait UserAgentRepository:
  /** Looks up a previously stored user agent by id. Returns [[None]] if the id is
   *  unknown or its row has already expired.
   */
  def find(id: UserAgentId): Task[Option[UserAgentDetails]]

  /** Looks up several previously stored user agents by id in a single query. Ids that
   *  are unknown or whose row has already expired are simply absent from the result
   *  map, rather than causing a failure.
   */
  def findMany(ids: List[UserAgentId]): Task[Map[UserAgentId, UserAgentDetails]]

  /** Inserts a new `user_agents` row under the given id, owned by `data.userId`.
   *  Idempotent: a no-op if a row with this id already exists (e.g. concurrent requests
   *  racing to (re)create the same cookie-carried id).
   */
  def create(id: UserAgentId, data: UserAgentData, ttl: Duration): Task[Unit]

  /** Slides the expiry of an existing row forward and (re)binds it to `data.userId` —
   *  the user currently completing login on this device. If a different user previously
   *  owned this row, ownership is transferred to `data.userId`. Refreshes the stored
   *  [[UserAgentDetails]] with the latest parse (e.g. a browser/OS update on the same
   *  device). Returns `true` if a row was found and updated, `false` if the id is
   *  unknown (e.g. expired and cleaned up).
   */
  def touch(id: UserAgentId, data: UserAgentData, ttl: Duration): Task[Boolean]

  /** The user's remembered user agents (rows that have not expired), most recently signed-in
   *  first, with everything the row stores.
   */
  def findByUserId(userId: UserId): Task[List[UserAgentRecord]]

  /** Forgets a user agent: deletes the row and, in the same transaction, expires the sessions
   *  that ran under it and the refresh tokens bound to those sessions. Refresh tokens name only
   *  their session, so a token whose session row has already been swept cannot be reached from
   *  here. Returns `false` when the id is not this user's.
   */
  def forget(id: UserAgentId, userId: UserId): Task[Boolean]

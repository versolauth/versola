package versola.oauth.session

import versola.oauth.model.UserAgentData
import versola.oauth.session.model.{UserAgentDetails, UserAgentId}
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

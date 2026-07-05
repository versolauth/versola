package versola.oauth.session

import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.user.model.UserId
import versola.util.MAC
import zio.*

trait SessionRepository:
  def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: Duration,
      idleTtl: Option[Duration],
  ): Task[Unit]

  def find(id: MAC.Of[SessionId]): Task[Option[SessionRecord]]

  /** Slide the idle expiry of an online session forward. No-op for sessions created without an idle window. */
  def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit]

  def findByUserId(
      userId: UserId,
  ): Task[List[SessionRecord]]

  /** Find active sessions for a user together with their opaque IDs.
    * The returned MAC is safe to expose as a base64url string to the authenticated user.
    */
  def findByUserIdWithId(
      userId: UserId,
  ): Task[List[(MAC.Of[SessionId], SessionRecord)]]

  def invalidateByUserId(
      userId: UserId,
  ): Task[Unit]

  /** Invalidate a single session belonging to the given user.
    * The userId constraint prevents users from invalidating each other's sessions.
    */
  def invalidate(id: MAC.Of[SessionId], userId: UserId): Task[Unit]

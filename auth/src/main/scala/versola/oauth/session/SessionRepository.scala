package versola.oauth.session

import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.user.model.UserId
import versola.util.MAC
import zio.*

import java.util.UUID

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

  /** Find active sessions for a user together with their public UUIDs (UUIDv7).
    * The returned UUID is safe to expose to the authenticated user.
    */
  def findByUserIdWithId(
      userId: UserId,
  ): Task[List[(UUID, SessionRecord)]]

  def invalidateByUserId(
      userId: UserId,
  ): Task[Unit]

  /** Invalidate a single session by its public UUID.
    * The UUID is unguessable; the caller must only obtain it via findByUserIdWithId.
    */
  def invalidate(publicSessionId: UUID): Task[Unit]

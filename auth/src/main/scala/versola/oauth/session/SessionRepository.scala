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

  /** Atomically expires all sessions and deletes all refresh tokens for the given user.
   *  Intended for admin-panel use (e.g. force-logout). */
  def invalidateByUserId(
      userId: UserId,
  ): Task[Unit]

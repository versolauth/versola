package versola.oauth.session

import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{RefreshAlreadyExchanged, RefreshTokenRecord, SessionId, SessionRecord}
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

  def findSession(id: MAC.Of[SessionId]): Task[Option[SessionRecord]]

  /** Slide the idle expiry of an online session forward. No-op for sessions created without an idle window. */
  def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit]

  def findByUserId(
      userId: UserId,
  ): Task[List[SessionRecord]]

  /** Atomically expires all sessions and refresh tokens for the given user.
   *  Intended for admin-panel use (e.g. force-logout). */
  def invalidateByUserId(
      userId: UserId,
  ): Task[Unit]

  def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit]

  def findToken(token: MAC.Of[RefreshToken]): Task[Option[RefreshTokenRecord]]

  def delete(token: MAC.Of[RefreshToken]): Task[Unit]

  def deleteByAccessToken(token: AccessToken): Task[Unit]

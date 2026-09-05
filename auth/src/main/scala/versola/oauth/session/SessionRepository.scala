package versola.oauth.session

import versola.oauth.client.model.ClientId
import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{PriorSession, PublicSessionId, RefreshAlreadyExchanged, RefreshTokenRecord, RevokedFamily, SessionId, SessionRecord}
import versola.user.model.UserId
import versola.util.MAC
import zio.*

import java.time.Instant

trait SessionRepository:
  /** Creates a new session in a single transaction.
   *  When `priorSession` is provided the INSERT and prior-session handling are atomic.
   *  [[PriorSession.Invalidate]] expires the prior session and all its refresh tokens.
   *  [[PriorSession.MigrateTokens]] expires the prior session but re-parents its refresh
   *  tokens to the new session with updated auth context.
   */
  def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: Duration,
      idleTtl: Option[Duration],
      priorSession: Option[PriorSession],
  ): Task[Unit]

  def findSession(id: MAC.Of[SessionId]): Task[Option[SessionRecord]]

  /** Slide the idle expiry of an online session forward. No-op for sessions created without an idle window. */
  def prolongIdle(id: MAC.Of[SessionId], idleTtl: Duration): Task[Unit]

  /** Registers a relying party as logged-in on this session, so it can be notified on
   *  front/back-channel logout. Idempotent: a no-op if the client is already registered. */
  def registerClient(id: MAC.Of[SessionId], clientId: ClientId): Task[Unit]

  def findByUserId(
      userId: UserId,
  ): Task[List[SessionRecord]]

  /** Atomically expires all active sessions and refresh tokens for the given user,
   *  returning the sessions that were invalidated so callers (e.g. admin-panel
   *  force-logout) can fan out back-channel logout to their participating clients
   *  without a separate lookup. Intended for admin-panel use (e.g. force-logout). */
  def invalidateByUserId(
      userId: UserId,
  ): Task[List[SessionRecord]]

  def invalidate(id: MAC.Of[SessionId]): Task[Option[SessionRecord]]

  def invalidateByPublicId(publicId: PublicSessionId): Task[Option[(MAC.Of[SessionId], SessionRecord)]]

  def invalidateByPublicIdForUser(publicId: PublicSessionId, userId: UserId): Task[Boolean]

  /** Issues `refreshToken`. With `previous` set this is a rotation: the presented token is
    * retired in place and the new one joins its family, both under the family's row lock, so
    * a rotation cannot interleave with a [[revokeFamily]] and leave its successor behind.
    * Fails with `RefreshAlreadyExchanged` when the presented token was already retired --
    * either sequentially or by a concurrent request that won the lock first.
    */
  def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      previous: Option[MAC.Of[RefreshToken]],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit]

  def findToken(token: MAC.Of[RefreshToken]): Task[Option[RefreshTokenRecord]]

  /** Revokes the whole rotation family of an already-retired `token`, i.e. one presented
    * after it was exchanged for its successor. That is a proven leak -- to whoever stole it,
    * or back to its rightful owner after a thief's exchange won the race -- so no member of
    * the family can be trusted anymore, however many generations have passed since.
    *
    * Every member is expired in place (collected by the cleanup manager's `expires_at` sweep
    * like any other expiry in this table, rather than deleted inline). Members issued after
    * `accessTokensIssuedAfter` are returned so the caller can push their access tokens to the
    * client's back channel; older ones are past their access-token TTL and not worth pushing.
    *
    * Returns `None` when `token` is not a retired member of a family owned by `clientId`:
    * unknown, still live, or belonging to someone else. Scoping to `clientId` keeps one
    * client from using another's retired token as an oracle to kill a family it does not own.
    */
  def revokeFamily(
      token: MAC.Of[RefreshToken],
      clientId: ClientId,
      accessTokensIssuedAfter: Instant,
  ): Task[Option[RevokedFamily]]

  def delete(token: MAC.Of[RefreshToken]): Task[Unit]

  def deleteByAccessToken(token: AccessToken): Task[Unit]

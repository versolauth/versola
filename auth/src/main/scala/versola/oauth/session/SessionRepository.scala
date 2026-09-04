package versola.oauth.session

import versola.oauth.client.model.ClientId
import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{PriorSession, PublicSessionId, RefreshAlreadyExchanged, RefreshTokenRecord, SessionId, SessionRecord}
import versola.user.model.UserId
import versola.util.MAC
import zio.*

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

  def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit]

  def findToken(token: MAC.Of[RefreshToken]): Task[Option[RefreshTokenRecord]]

  /** Detects refresh-token replay: `token` has no row of its own but is named as some other
   *  row's `previous_id`, i.e. it was already rotated away and is now being presented a
   *  second time -- by whoever stole it, or by its rightful owner after the thief's use
   *  already won the rotation race. Either way neither party can be trusted with the
   *  chain's live end anymore, so it is expired in place (picked up by the cleanup manager
   *  like any other expired row, rather than deleted inline) and its access token returned
   *  so the caller can push a revocation to the client.
   *
   *  Scoped to `clientId` so one client cannot use another client's already-rotated token
   *  as an oracle to expire a chain it does not own.
   */
  def markChainReplayed(token: MAC.Of[RefreshToken], clientId: ClientId): Task[Option[(UserId, AccessToken)]]

  def delete(token: MAC.Of[RefreshToken]): Task[Unit]

  def deleteByAccessToken(token: AccessToken): Task[Unit]

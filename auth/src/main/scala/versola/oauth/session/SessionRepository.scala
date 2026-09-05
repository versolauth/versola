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
    *
    * `idempotencyKey` is recorded against the token being retired, so that the exchange can
    * be recognised later if the client repeats it -- see [[findIdempotentRetry]].
    */
  def createRefreshToken(
      refreshToken: MAC.Of[RefreshToken],
      previous: Option[MAC.Of[RefreshToken]],
      record: RefreshTokenRecord,
      idempotencyKey: Option[MAC],
  ): IO[Throwable | RefreshAlreadyExchanged, Unit]

  /** Resolves a repeat of an exchange that already happened, for a client that never received
    * its response. Returns the family's live tip, with its id, so the caller can continue the
    * chain from there: the original response -- and the token inside it -- is not recoverable,
    * only re-minted.
    *
    * Returns `None` unless the family's most recent exchange was made by this client carrying
    * this exact key, and left a live tip behind. Only one row in a family holds a key at a
    * time -- [[createRefreshToken]] moves it onto whichever token it retires -- so matching it
    * is the same as asking whether the chain has moved on since. It has not while the client
    * is still retrying, however many times; it has the moment the client gets through and
    * refreshes under a new key, at which point the old one stops being honoured with nothing
    * needing to expire or be cleared.
    *
    * A caller holding the token but not the key still reads as a replay, which is what keeps
    * this from weakening reuse detection.
    */
  def findIdempotentRetry(
      token: MAC.Of[RefreshToken],
      clientId: ClientId,
      idempotencyKey: MAC,
  ): Task[Option[(MAC.Of[RefreshToken], RefreshTokenRecord)]]

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

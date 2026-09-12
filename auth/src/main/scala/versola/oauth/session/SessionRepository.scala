package versola.oauth.session

import versola.oauth.client.model.{ClientId, ScopeToken}
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

  /** Every currently-live refresh token issued to a user, for admin-panel display. Looked up
    * directly by `user_id` rather than through the user's sessions: a refresh token's expiry
    * slides forward on every use while a session's does not, so a token routinely outlives the
    * session it was issued under, and one whose session has already expired (or been swept)
    * must still show up here.
    */
  def findRefreshTokensByUserId(
      userId: UserId,
  ): Task[List[RefreshTokenRecord]]

  /** Atomically expires all active sessions and refresh tokens for the given user,
   *  returning the sessions that were invalidated so callers (e.g. admin-panel
   *  force-logout) can fan out back-channel logout to their participating clients
   *  without a separate lookup. Intended for admin-panel use (e.g. force-logout).
   *
   *  Reaches refresh_tokens by user_id directly rather than through the sessions just
   *  expired above -- a token can be live long after its session expired (see
   *  [[findRefreshTokensByUserId]]), and force-logout has to revoke those too. */
  def invalidateByUserId(
      userId: UserId,
  ): Task[List[SessionRecord]]

  def invalidate(id: MAC.Of[SessionId]): Task[Option[SessionRecord]]

  def invalidateByPublicId(publicId: PublicSessionId): Task[Option[(MAC.Of[SessionId], SessionRecord)]]

  def invalidateByPublicIdForUser(publicId: PublicSessionId, userId: UserId): Task[Boolean]

  /** Issues `refreshToken`. With `previous` set this is a rotation: the presented token is
    * retired in place and the new one joins its family, both under the family's advisory
    * lock, so a rotation cannot interleave with a [[revokeFamily]] and leave its successor
    * behind.
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
    * like any other expiry in this table, rather than deleted inline). Members whose access
    * token has not yet expired -- per each row's own `access_token_expires_at`, not the
    * client's current `accessTokenTtl`, which is mutable -- are returned so the caller can
    * push their access tokens to the client's back channel; the rest are already dead and not
    * worth pushing.
    *
    * Returns `None` when `token` is not a retired member of a family owned by `clientId`:
    * unknown, still live, or belonging to someone else. Scoping to `clientId` keeps one
    * client from using another's retired token as an oracle to kill a family it does not own.
    */
  def revokeFamily(
      token: MAC.Of[RefreshToken],
      clientId: ClientId,
  ): Task[Option[RevokedFamily]]

  /** Refreshes a sender-constrained (DPoP-bound) token in place: re-points `access_token` at
    * the one just issued, narrows `scope` to what this refresh was granted and slides the
    * expiry, without rotating. Rotation exists to detect a stolen token being used; a bound
    * token cannot be used by whoever copied it, so the chain, its retained generations and the
    * idempotency key that makes rotation retryable are all unnecessary here.
    *
    * `scope` is written for the same reason the rotating path writes it into the successor:
    * RFC 6749 §6 narrowing has to outlive the request that asked for it, or the next refresh
    * -- which names no scope of its own -- would hand back what the client just dropped. This
    * row is the grant's only record, so leaving it alone would keep the wider scope
    * authoritative.
    *
    * `accessTokenExpiresAt` is written for the same reason: a family revocation reads this
    * row's own expiry, not the client's current `accessTokenTtl`, to decide whether the token
    * it names is still worth pushing to the edge.
    *
    * Returns false when the token is gone, expired or retired.
    */
  def renewBoundToken(
      token: MAC.Of[RefreshToken],
      accessToken: AccessToken,
      scope: Set[ScopeToken],
      expiresAt: Instant,
      accessTokenExpiresAt: Instant,
  ): Task[Boolean]

  def delete(token: MAC.Of[RefreshToken]): Task[Unit]

  /** Revokes the one refresh token issued alongside `token`, an access token being rejected as
    * the product of a replayed authorization code. `sessionId` narrows the search to a handful
    * of rows before `token` is checked, since `access_token` carries no index of its own -- see
    * the schema comment on `refresh_tokens.access_token`.
    */
  def deleteByAccessToken(sessionId: MAC.Of[SessionId], token: AccessToken): Task[Unit]

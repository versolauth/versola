package versola.oauth.revoke

import versola.oauth.client.model.OAuthClientRecord
import versola.oauth.logout.BackChannelOutbox
import versola.oauth.model.AccessTokenPayload
import versola.oauth.revocation.{AuthRevocationKeys, AuthRevocationRepository, RevocationCause}
import versola.oauth.session.model.RefreshTokenFamilyId
import versola.util.revocation.{Revocation, RevocationCache, RevocationKey}
import zio.json.ast.Json
import zio.{NonEmptyChunk, Task, UIO, ZIO, ZLayer}

import java.time.Instant

/** What happens to access tokens when something ends them: auth's own record of it (the
  * `revocations` table, read by [[isRevoked]]) and the notification to the client's back channel.
  *
  * Auth stores no access tokens. What it stores is the revocations: a row that names what was ended
  * (one token, a refresh family, a session, a user) for as long as a token it covers could still be
  * valid. The row is written in the transaction that ends the thing the token depends on (see
  * `SessionRepository`), so this service only writes it itself where the revocation is the whole of
  * the request, a client's `/revoke` of an access token.
  */
trait AccessTokenRevocationService:
  /** A client's `/revoke` of one access token: records `jti` until the token's own `exp`, which was
    * read off the token and is exact, then tells the client's back channel.
    *
    * The record is what makes the token refused at `/userinfo` and `/introspect` whether or not the
    * client registered a back channel; the notification is for the components that check JWTs on
    * their own signature (`edge`, resource servers).
    */
  def revoke(client: OAuthClientRecord, token: AccessTokenPayload): Task[Unit]

  /** Tells the client's back channel to stop accepting every access token a refresh-token family
    * issued, named by the family rather than one `jti` at a time. The record of it, `fam`, was
    * written by the repository method that ended the family, in its transaction.
    *
    * What makes this the only workable shape for a leaked chain: the tokens it issued are not in
    * hand. Auth keeps no record of them (each one's `fam` claim is what ties it back here), so one
    * minted by a generation that has since rotated away, or by a row the cleanup sweep has taken, is
    * covered by this and could not be covered by a list of ids.
    *
    * @param expiresAt an upper bound, derived from the client's current `accessTokenTtl` since no
    *                  token is at hand to read an `exp` off. Over-retains, never under-retains.
    */
  def announceFamily(client: OAuthClientRecord, family: RefreshTokenFamilyId, subject: String, expiresAt: Instant): Task[Unit]

  /** The same for every family one subject held under the client, in one event (`revoked_fam` is an array), and for a caller
    * that must not lose it: the delivery waits for room in the outbox rather than being dropped when it is full (#962: every
    * grant of a partner's client ends at once). */
  def announceFamilies(client: OAuthClientRecord, families: NonEmptyChunk[RefreshTokenFamilyId], subject: String, expiresAt: Instant): Task[Unit]

  /** Whether this token was revoked. Answered from the replica's in-memory list, so it reflects a
    * revocation once its notification has reached this replica (typically within a second), not
    * the instant it was written.
    */
  def isRevoked(token: AccessTokenPayload): UIO[Boolean]

object AccessTokenRevocationService:
  /** Distinct from OIDC's `backchannel-logout` event on purpose: that one ends an SSO session and
    * every client's participation in it, while this names the tokens of one grant and leaves the
    * session running. Reusing it would let one client's `/revoke` log every other client sharing
    * the session out.
    */
  private val AccessTokenRevocationEvent = "versola:event:access-token-revocation"

  def live: ZLayer[BackChannelOutbox & AuthRevocationRepository & RevocationCache, Nothing, AccessTokenRevocationService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      outbox: BackChannelOutbox,
      repository: AuthRevocationRepository,
      cache: RevocationCache,
  ) extends AccessTokenRevocationService:

    override def revoke(client: OAuthClientRecord, token: AccessTokenPayload): Task[Unit] =
      repository.revoke(
        Revocation(key = RevocationKey.Jti(token.id.encoded), expiresAt = token.expiresAt, issuedBefore = None),
        RevocationCause.Revoke,
        Some(client.tenantId),
      ) *>
        // Not `jti`/`exp`: those are the event token's own id and lifetime (two minutes), and
        // overwriting them would both strip the event of a replay id and leave the recipient
        // reading the event's expiry as the revoked token's. `revoked_jti` is always an array,
        // even for one token, so the recipient has one shape to parse rather than a
        // singular-or-array ambiguity.
        push(
          client,
          token.subject,
          token.expiresAt,
          "revoked_jti" -> Json.Arr(NonEmptyChunk(token.id).toChunk.map(t => Json.Str(t.encoded))),
        )

    /** `revoked_fam` in place of `revoked_jti`, an array for the same reason that one is. */
    override def announceFamily(
        client: OAuthClientRecord,
        family: RefreshTokenFamilyId,
        subject: String,
        expiresAt: Instant,
    ): Task[Unit] =
      push(client, subject, expiresAt, "revoked_fam" -> Json.Arr(Json.Str(family)))

    override def announceFamilies(
        client: OAuthClientRecord,
        families: NonEmptyChunk[RefreshTokenFamilyId],
        subject: String,
        expiresAt: Instant,
    ): Task[Unit] =
      push(client, subject, expiresAt, "revoked_fam" -> Json.Arr(families.map(Json.Str(_))), reliably = true)

    /** Handed to the outbox rather than delivered here: unlike the single attempt this used to be,
      * the outbox retries, so an `edge` that was unreachable for a moment still learns of the
      * revocation. It stays a notification and not the record of it: the local state the caller
      * asked to change is already committed by the time this runs, and a client with no
      * `back_channel_logout_uri` has no address to push to, so nothing is delivered. Per RFC 7009
      * the caller gets a success either way.
      */
    private def push(
        client: OAuthClientRecord,
        subject: String,
        expiresAt: Instant,
        revoked: (String, Json),
        reliably: Boolean = false,
    ): UIO[Unit] =
      ZIO.foreachDiscard(client.backChannelLogoutUri): uri =>
        (if reliably then outbox.submitAwaiting else outbox.submit) (
          BackChannelOutbox.Delivery(
            audience = NonEmptyChunk(client.id),
            tenantId = client.tenantId,
            uri = uri,
            subject = subject,
            customClaims = Json.Obj(
              revoked,
              "revoked_exp" -> Json.Num(expiresAt.getEpochSecond),
              "events" -> Json.Obj(AccessTokenRevocationEvent -> Json.Obj()),
            ),
          ),
        )

    override def isRevoked(token: AccessTokenPayload): UIO[Boolean] =
      cache.isRevoked(AuthRevocationKeys.of(token), token.issuedAt)

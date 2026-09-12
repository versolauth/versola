package versola.oauth.revoke

import versola.oauth.client.model.OAuthClientRecord
import versola.oauth.logout.BackChannelDispatcher
import versola.oauth.model.AccessToken
import zio.json.ast.Json
import zio.{NonEmptyChunk, Task, ZIO, ZLayer}

import java.time.Instant

trait AccessTokenRevocationService:
  /** Tells the client's back channel to stop accepting one or more access tokens before
    * `expiresAt`.
    *
    * `expiresAt` is shared by every token in the batch rather than given per token, and is
    * an upper bound rather than each token's own `exp`: one token's real expiry when the
    * batch holds one, the furthest among them when it holds several. A token whose own `exp`
    * came earlier is then held at the edge past it -- over-retained, never under-retained.
    * That is what lets a family with several access tokens be named in one event instead of
    * one push per token.
    *
    * @param subject  whom the tokens were issued to — the user, or the client itself for a
    *                 `client_credentials` token.
    * @param expiresAt when the last of the tokens expires on its own, after which the
    *                  revocation stops mattering and can be forgotten.
    */
  def revoke(client: OAuthClientRecord, tokens: NonEmptyChunk[AccessToken], subject: String, expiresAt: Instant): Task[Unit]

  def isActive(token: AccessToken): Task[Boolean]

object AccessTokenRevocationService:
  /** Distinct from OIDC's `backchannel-logout` event on purpose: that one ends an SSO
    * session and every client's participation in it, while this names a single token and
    * leaves the session running. Reusing it would let one client's `/revoke` log every
    * other client sharing the session out.
    */
  private val AccessTokenRevocationEvent = "versola:event:access-token-revocation"

  def live: ZLayer[BackChannelDispatcher, Nothing, AccessTokenRevocationService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(dispatcher: BackChannelDispatcher) extends AccessTokenRevocationService:

    /** Delivered synchronously, unlike back-channel logout: logout fans out to every client
      * of a session and forks each delivery so one slow RP cannot hold up the others, while
      * `/revoke` names exactly one client and makes exactly one call.
      *
      * A client with no `back_channel_logout_uri` has no address to push to, so nothing is
      * delivered. Per RFC 7009 the caller still gets a success either way: the local state
      * is already committed, and a token cannot be guaranteed to be rejected everywhere
      * unless the client registered an endpoint for exactly that.
      */
    override def revoke(client: OAuthClientRecord, tokens: NonEmptyChunk[AccessToken], subject: String, expiresAt: Instant): Task[Unit] =
      ZIO
        .foreachDiscard(client.backChannelLogoutUri): uri =>
          dispatcher.dispatch(
            audience = NonEmptyChunk(client.id),
            uri = uri,
            subject = subject,
            // Not `jti`/`exp`: those are the event token's own id and lifetime (two minutes),
            // and overwriting them would both strip the event of a replay id and leave the
            // recipient reading the event's expiry as the revoked token's. `revoked_jti` is
            // always an array, even for one token, so the recipient has one shape to parse
            // rather than a singular-or-array ambiguity.
            customClaims = Json.Obj(
              "revoked_jti" -> Json.Arr(tokens.toChunk.map(t => Json.Str(t.encoded))),
              "revoked_exp" -> Json.Num(expiresAt.getEpochSecond),
              "events" -> Json.Obj(AccessTokenRevocationEvent -> Json.Obj()),
            ),
          )
        // The local state the caller asked to change is already committed by the time this
        // runs, and RFC 7009 does not promise that a revoked token is rejected everywhere
        // immediately — so a failed push is logged, not turned into an error response.
        .catchAllCause(cause => ZIO.logWarningCause(s"Access token revocation push to client '${client.id}' failed", cause))

    /** Introspection still reports a revoked access token as active until it expires:
      * answering otherwise needs auth to keep its own record of revocations, which is
      * separate scope from having the edge reject them.
      */
    override def isActive(token: AccessToken): Task[Boolean] =
      ZIO.succeed(true)

  def noop: ZLayer[Any, Nothing, AccessTokenRevocationService] =
    ZLayer.succeed(NoopImpl())

  private class NoopImpl extends AccessTokenRevocationService:
    override def revoke(client: OAuthClientRecord, tokens: NonEmptyChunk[AccessToken], subject: String, expiresAt: Instant): Task[Unit] =
      ZIO.unit

    override def isActive(token: AccessToken): Task[Boolean] =
      ZIO.succeed(true)

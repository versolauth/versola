package versola.edge.session

import versola.edge.model.{AccessTokenId, PresetId, SessionId}
import versola.util.Secret

import java.time.Instant

/** One row per (SSO session, preset) participation. Written on every successful
  * login and refresh, whether or not the OP issued a refresh token, so logout can
  * enumerate every preset whose EDGE_SESSION cookie has to be cleared.
  */
case class EdgeSessionRecord(
    publicSessionId: SessionId,
    presetId: PresetId,
    accessTokenId: AccessTokenId,
    encryptedRefreshToken: Option[Secret],
    expiresAt: Instant,
    /** The `accessTokenId` this row had before its last rotation. Set by the repository on rotation
      * and read-only for callers: whatever is passed to `create` is ignored.
      */
    previousAccessTokenId: Option[AccessTokenId],
    /** The current access token, sealed like the refresh token, so a request that lost a refresh race
      * can be handed the winner's token instead of presenting the refresh token a second time.
      */
    encryptedAccessToken: Option[Secret],
    /** When this row last changed its `accessTokenId`; read-only like `previousAccessTokenId`. */
    rotatedAt: Option[Instant],
)

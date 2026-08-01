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
)

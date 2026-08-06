package versola.oauth.session.model

import versola.oauth.client.model.{Acr, AuthMethodRef, ClientId, ResourceId, ScopeToken}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.MAC
import zio.prelude.Equal

import java.time.Instant

given Equal[Instant] = Equal.default

case class RefreshTokenRecord(
    sessionId: MAC.Of[SessionId],
    publicSessionId: PublicSessionId,
    accessToken: AccessToken,
    userId: UserId,
    clientId: ClientId,
    /** RFC 8707 `resource` parameter(s) requested at `/authorize`; carried into the `aud`
      * claim of access tokens issued from this refresh token (and its successors). */
    resources: List[ResourceId],
    scope: Set[ScopeToken],
    issuedAt: Instant,
    expiresAt: Instant,
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    nonce: Option[Nonce],
    previousRefreshToken: Option[MAC.Of[RefreshToken]],
    amr: Set[AuthMethodRef],
    authTime: Instant,
    acr: Option[Acr],
) derives CanEqual, Equal

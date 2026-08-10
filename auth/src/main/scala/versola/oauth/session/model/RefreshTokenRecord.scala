package versola.oauth.session.model

import versola.oauth.client.model.{Acr, AuthMethodRef, ClientId, ResourceUri, ScopeToken}
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
    /** The resolved resource audience carried into access tokens issued from this refresh token
      * and its successors. */
    audience: List[ResourceUri],
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

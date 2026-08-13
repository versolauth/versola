package versola.oauth.token.model

import versola.oauth.client.model.{Acr, AuthMethodRef, AuthorizationDetail, ClientId, ResourceUri, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.role.model.RoleId
import versola.oauth.session.model.PublicSessionId
import versola.user.model.{UserId, UserRecord}
import zio.Duration

import java.time.Instant

case class IssuedTokens(
    accessToken: AccessToken,
    clientId: ClientId,
    audience: List[ResourceUri],
    /** RFC 9396 authorization details granted for these tokens; echoed in the token response
      * and carried as the access token's `authorization_details` claim. */
    authorizationDetails: List[AuthorizationDetail],
    accessTokenTtl: Duration,
    userId: Option[UserId], // None for client_credentials grant
    refreshToken: Option[RefreshToken],
    scope: Set[ScopeToken],
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    nonce: Option[Nonce],
    user: Option[UserRecord],
    tenantId: TenantId, // every client belongs to a tenant, including client_credentials
    roles: List[RoleId], // role IDs within tenantId; empty for client_credentials
    sessionId: Option[PublicSessionId],
    amr: Set[AuthMethodRef],
    authTime: Option[Instant], // None for client_credentials grant
    acr: Option[Acr],
)
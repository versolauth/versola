package versola.oauth.token.model

import versola.oauth.client.model.{Acr, AuthMethodRef, ClientId, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.role.model.RoleId
import versola.user.model.{UserId, UserRecord}
import zio.Duration

import java.time.Instant

case class IssuedTokens(
    accessToken: AccessToken,
    clientId: ClientId,
    audience: List[ClientId],
    accessTokenTtl: Duration,
    userId: Option[UserId], // None for client_credentials grant
    refreshToken: Option[RefreshToken],
    scope: Set[ScopeToken],
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    nonce: Option[Nonce],
    user: Option[UserRecord],
    tenantId: Option[TenantId], // None for client_credentials service tokens
    roles: List[RoleId],        // role IDs within tenantId; empty for client_credentials
    amr: Set[AuthMethodRef],
    authTime: Option[Instant], // None for client_credentials grant
    acr: Option[Acr],
)
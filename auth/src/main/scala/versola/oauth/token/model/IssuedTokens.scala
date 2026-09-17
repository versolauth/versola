package versola.oauth.token.model

import versola.oauth.client.model.{Acr, AuthMethodRef, AuthorizationDetail, ClientId, ResourceUri, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.role.model.RoleId
import versola.oauth.session.model.{PublicSessionId, RefreshTokenFamilyId}
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
    /** The refresh-token family this access token is issued from, carried as its `fam` claim
      * so that revoking the family reaches it. `None` for `client_credentials`, the one grant
      * with no chain behind it. Present even when the grant asked for no `offline_access` and
      * no chain was stored: the id still names what a replay of the code would revoke. */
    refreshTokenFamilyId: Option[RefreshTokenFamilyId],
    amr: Set[AuthMethodRef],
    authTime: Option[Instant], // None for client_credentials grant
    acr: Option[Acr],
    /** RFC 9449 §6: the JWK thumbprint the access token is bound to, carried as its `cnf.jkt`
      * claim; `None` for a bearer token. */
    cnfJkt: Option[String],
)
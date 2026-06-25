package versola.oauth.token.model

import versola.oauth.client.model.{AuthMethodRef, ClientId, ScopeToken}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.userinfo.model.RequestedClaims
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
    roles: List[String], // role IDs for user tokens; empty for client_credentials
    amr: Set[AuthMethodRef],
    authTime: Option[Instant], // None for client_credentials grant
)
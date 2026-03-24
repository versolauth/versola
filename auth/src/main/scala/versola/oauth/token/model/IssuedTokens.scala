package versola.oauth.token.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.{UserId, UserRecord}
import zio.Duration

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
)
package versola.oauth.token.model

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
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
)
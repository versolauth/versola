package versola.oauth.token.model

import versola.auth.model.RefreshToken
import versola.oauth.client.model.{ClientId, ClientSecret, ScopeToken}
import versola.oauth.model.{AuthorizationCode, CodeVerifier, GrantType}
import zio.http.URL

sealed trait TokenRequest

case class CodeExchangeRequest(
    code: AuthorizationCode,
    redirectUri: URL,
    codeVerifier: CodeVerifier,
) extends TokenRequest

case class RefreshTokenRequest(
    refreshToken: RefreshToken,
    scope: Option[Set[ScopeToken]],
) extends TokenRequest

case class ClientCredentialsRequest(
    scope: Option[Set[ScopeToken]],
) extends TokenRequest

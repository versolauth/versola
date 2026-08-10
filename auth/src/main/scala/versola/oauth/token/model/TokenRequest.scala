package versola.oauth.token.model

import versola.oauth.client.model.{ClientSecret, ResourceUri, ScopeToken}
import versola.oauth.model.{AuthorizationCode, CodeVerifier, GrantType, RefreshToken}
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
    resources: Option[List[ResourceUri]],
) extends TokenRequest

case class ClientCredentialsRequest(
    scope: Option[Set[ScopeToken]],
    resources: Option[List[ResourceUri]],
) extends TokenRequest

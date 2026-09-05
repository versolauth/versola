package versola.oauth.token.model

import versola.oauth.client.model.{AuthorizationDetail, ClientSecret, ResourceUri, ScopeToken}
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
    /** RFC 9396 §6: when present, narrows the granted authorization details to those requested. */
    authorizationDetails: Option[List[AuthorizationDetail]],
) extends TokenRequest

case class ClientCredentialsRequest(
    scope: Option[Set[ScopeToken]],
    resources: Option[List[ResourceUri]],
    authorizationDetails: Option[List[AuthorizationDetail]],
) extends TokenRequest

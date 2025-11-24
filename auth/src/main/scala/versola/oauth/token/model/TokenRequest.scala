package versola.oauth.token.model

import versola.oauth.client.model.{ClientId, ClientSecret}
import versola.oauth.model.{AuthorizationCode, CodeVerifier, GrantType}
import zio.http.URL

sealed trait TokenRequest

case class CodeExchangeRequest(
    code: AuthorizationCode,
    redirectUri: URL,
    codeVerifier: CodeVerifier,
) extends TokenRequest

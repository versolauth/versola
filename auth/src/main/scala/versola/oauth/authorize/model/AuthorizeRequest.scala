package versola.oauth.authorize.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod, State}
import versola.util.Base64Url
import zio.http.URL
import zio.prelude.NonEmptySet

private[authorize]
case class AuthorizeRequest(
    clientId: ClientId,
    redirectUri: URL,
    scope: Set[ScopeToken],
    state: Option[State],
    codeChallenge: CodeChallenge,
    codeChallengeMethod: CodeChallengeMethod,
    responseType: NonEmptySet[ResponseTypeEntry],
):
  def buildResponseUri(code: AuthorizationCode): URL =
    val params = List(
      "code" -> Base64Url.encode(code),
    ) ++ state.map("state" -> _)
    redirectUri.addQueryParams(params)
    

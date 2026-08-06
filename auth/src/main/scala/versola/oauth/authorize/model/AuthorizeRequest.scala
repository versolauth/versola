package versola.oauth.authorize.model

import versola.oauth.client.model.{Acr, ClientId, ResourceId, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, Nonce, State}
import versola.oauth.session.model.SessionId
import versola.oauth.userinfo.model.RequestedClaims
import versola.util.{Email, Phone}
import zio.http.URL
import zio.prelude.{NonEmptyList, NonEmptySet}

private[authorize] case class AuthorizeRequest(
    clientId: ClientId,
    redirectUri: URL,
    scope: Set[ScopeToken],
    state: Option[State],
    codeChallenge: CodeChallenge,
    codeChallengeMethod: CodeChallengeMethod,
    responseType: NonEmptySet[ResponseTypeEntry],
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    nonce: Option[Nonce],
    userAgent: Option[String],
    prompt: Set[Prompt],
    maxAge: Option[Long],
    acrValues: Option[NonEmptyList[Acr]],
    sessionId: Option[SessionId],
    loginHint: Option[Either[Email, Phone]],
    idTokenHint: Option[String],
    /** RFC 8707 `resource` parameter(s), resolved to registered resource ids. */
    resources: List[ResourceId],
):
  def promptNone: Boolean = prompt.contains(Prompt.none)
  def promptLogin: Boolean = prompt.contains(Prompt.login)

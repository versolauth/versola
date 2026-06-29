package versola.oauth.authorize.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, Nonce, State}
import versola.oauth.session.model.SessionId
import versola.oauth.userinfo.model.RequestedClaims
import versola.util.{Email, Phone}
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
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    nonce: Option[Nonce],
    userAgent: Option[String],
    prompt: Set[Prompt],
    maxAge: Option[Long],
    acrValues: Option[List[String]],
    sessionId: Option[SessionId],
    loginHint: Option[Either[Email, Phone]],
)

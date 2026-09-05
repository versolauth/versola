package versola.oauth.authorize.model

import versola.oauth.client.model.{Acr, AuthorizationDetail, ClientId, ResourceUri, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, Nonce, State}
import versola.oauth.model.UserAgentCookiePayload
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
    userAgentCookie: Option[UserAgentCookiePayload],
    prompt: Set[Prompt],
    maxAge: Option[Long],
    acrValues: Option[NonEmptyList[Acr]],
    sessionId: Option[SessionId],
    loginHint: Option[Either[Email, Phone]],
    idTokenHint: Option[String],
    /** RFC 8707 `resource` parameter(s), canonicalized for the access-token `aud` claim. */
    resources: List[ResourceUri],
    /** RFC 9396 `authorization_details`, validated against the tenant's type registry; `None`
      * when the parameter was absent, distinct from an empty list (which the parameter itself
      * disallows). */
    authorizationDetails: Option[List[AuthorizationDetail]],
    ip: Option[String] = None,
):
  def promptNone: Boolean    = prompt.contains(Prompt.none)
  def promptLogin: Boolean   = prompt.contains(Prompt.login)
  def promptConsent: Boolean = prompt.contains(Prompt.consent)
  def isHybrid: Boolean      = responseType.contains(ResponseTypeEntry.IdToken)

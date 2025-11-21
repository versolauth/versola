package versola.oauth.authorize.model

import versola.oauth.model.{ClientId, CodeChallenge, CodeChallengeMethod, ScopeToken, State}
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
)

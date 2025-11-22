package versola.oauth.model

import versola.user.model.UserId
import zio.schema.*

import java.time.Instant

/** 
 * Authorization code record stored in the database
 * Compliant with OAuth 2.0 (RFC 6749) and OAuth 2.1 (draft)
 */
case class AuthorizationCodeRecord(
    code: AuthorizationCode,
    clientId: ClientId,
    userId: UserId,
    redirectUri: String,
    scope: Set[ScopeToken],
    codeChallenge: CodeChallenge,
    codeChallengeMethod: CodeChallengeMethod,
    expiresAt: Instant,
    used: Boolean = false,
)


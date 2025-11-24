package versola.oauth.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.user.model.UserId
import zio.http.URL
import zio.schema.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

case class AuthorizationCodeRecord(
    clientId: ClientId,
    userId: UserId,
    redirectUri: URL,
    scope: Set[ScopeToken],
    codeChallenge: CodeChallenge,
    codeChallengeMethod: CodeChallengeMethod,
):
  def verify(verifier: CodeVerifier): Boolean =
    codeChallengeMethod match {
      case CodeChallengeMethod.S256 =>
        val digest = MessageDigest.getInstance("SHA-256")
          .digest(verifier.getBytes(StandardCharsets.UTF_8))

        val encoded = java.util.Base64.getUrlEncoder
          .withoutPadding()
          .encodeToString(digest)

        encoded == codeChallenge

      case CodeChallengeMethod.Plain =>
        verifier == codeChallenge
    }

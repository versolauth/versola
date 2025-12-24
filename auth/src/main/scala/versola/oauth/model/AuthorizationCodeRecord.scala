package versola.oauth.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.user.model.UserId
import versola.util.MAC
import zio.http.URL
import zio.prelude.Equal
import zio.schema.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

case class AuthorizationCodeRecord(
    sessionId: MAC.Of[SessionId],
    clientId: ClientId,
    userId: UserId,
    redirectUri: URL,
    scope: Set[ScopeToken],
    codeChallenge: CodeChallenge,
    codeChallengeMethod: CodeChallengeMethod,
) derives CanEqual, Equal:

  def session: SessionRecord = SessionRecord(
    userId = userId,
    clientId = clientId,
  )

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

object AuthorizationCodeRecord:
  given Equal[URL] = (a, b) => a == b

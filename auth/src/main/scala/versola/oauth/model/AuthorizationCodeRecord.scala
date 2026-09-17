package versola.oauth.model

import versola.oauth.client.model.{Acr, AuthMethodRef, AuthorizationDetail, ClientId, ResourceUri, ScopeToken}
import versola.oauth.session.model.{PublicSessionId, RefreshTokenFamilyId, RefreshTokenRecord, SessionId}
import versola.oauth.userinfo.model.RequestedClaims
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
    publicSessionId: PublicSessionId,
    clientId: ClientId,
    userId: UserId,
    redirectUri: URL,
    scope: Set[ScopeToken],
    codeChallenge: CodeChallenge,
    codeChallengeMethod: CodeChallengeMethod,
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    nonce: Option[Nonce],
    accessToken: AccessToken,
    /** The rotation family the exchange of this code will start. Generated here rather than at
      * the exchange because a replay of the code has nothing else to name what the first
      * exchange issued: the tokens themselves are not recorded, and the replaying caller
      * presents none of them. */
    familyId: RefreshTokenFamilyId,
    amr: Set[AuthMethodRef],
    authTime: Instant,
    acr: Option[Acr],
    /** RFC 8707 `resource` parameter(s) requested at `/authorize`; carried forward into the
      * issued access token's `aud` claim. */
    resources: List[ResourceUri],
    /** RFC 9396 `authorization_details` granted at `/authorize`; carried forward into the
      * issued access token and echoed in the token response. `None` when the parameter was
      * absent, distinct from an empty list (which the parameter itself disallows). */
    authorizationDetails: Option[List[AuthorizationDetail]],
    /** RFC 9449 §10 `dpop_jkt`: the key thumbprint the authorization request committed this
      * code to. `None` when the request named none, leaving redemption unconstrained. */
    dpopJkt: Option[String],
) derives CanEqual, Equal:

  def verify(verifier: CodeVerifier): Boolean =
    codeChallengeMethod match {
      case CodeChallengeMethod.S256 =>
        val digest = MessageDigest.getInstance("SHA-256")
          .digest(verifier.getBytes(StandardCharsets.UTF_8))

        val encoded = java.util.Base64.getUrlEncoder
          .withoutPadding()
          .encodeToString(digest)

        encoded == codeChallenge
    }

object AuthorizationCodeRecord:
  given Equal[URL] = (a, b) => a == b
  given Equal[Instant] = Equal.default

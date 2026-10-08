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
    /** RFC 7636 PKCE challenge the authorization request committed this code to. `None` only
      * where the request was allowed to omit PKCE (a confidential client of a `standard`
      * tenant), in which case redemption must not present a `code_verifier` either. */
    codeChallenge: Option[CodeChallenge],
    codeChallengeMethod: Option[CodeChallengeMethod],
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

  /** RFC 7636 §4.6, held both ways: a code committed to a challenge is redeemable only with the
    * matching verifier, and a code that committed to none is redeemable only without one -- a
    * verifier presented against nothing is a client that thinks it is protected and is not. */
  def verify(verifier: Option[CodeVerifier]): Boolean =
    (codeChallenge, codeChallengeMethod, verifier) match
      case (Some(challenge), Some(CodeChallengeMethod.S256), Some(verifier)) =>
        val digest = MessageDigest.getInstance("SHA-256")
          .digest(verifier.getBytes(StandardCharsets.UTF_8))

        val encoded = java.util.Base64.getUrlEncoder
          .withoutPadding()
          .encodeToString(digest)

        encoded == challenge

      case (None, None, None) =>
        true

      case _ =>
        false

object AuthorizationCodeRecord:
  given Equal[URL] = (a, b) => a == b
  given Equal[Instant] = Equal.default

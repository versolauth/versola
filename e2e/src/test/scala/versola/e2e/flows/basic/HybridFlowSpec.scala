package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Hybrid flow (`response_type=code id_token`, OIDC Core §3.3) happy path and its
  * implementation-verified edges.
  *
  * The `auth` module supports exactly two `response_type` values — `code` and
  * `code id_token` (see `AuthorizeRequestParser`, which matches those two literal
  * strings and rejects everything else, including `token`, `code token`, and
  * `code id_token token`, with `unsupported_response_type`). There is no per-client
  * allow-list for response types (`OAuthClientRecord` carries no such field): any
  * registered client may request either supported value, so a "client not registered
  * for hybrid" rejection does not exist in this implementation and is not tested here.
  *
  * `nonce` is REQUIRED whenever the response type includes `id_token` (OIDC Core
  * §3.1.2.1): `AuthorizeRequestParser` rejects a hybrid request without one with
  * `invalid_request`, delivered in the fragment like every other hybrid error. It stays
  * optional for the plain `code` flow.
  */
object HybridFlowSpec extends E2ESpec:

  private def decodeJwtPayload(jwt: String): String =
    String(Base64.getUrlDecoder.decode(jwt.split('.')(1)), StandardCharsets.UTF_8)

  /** OIDC Core §3.3.2.11: `c_hash` = base64url(left-half(SHA-256(ASCII(code)))). */
  private def expectedCHash(code: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding.encodeToString(digest.take(digest.length / 2))

  private case class HybridIdTokenClaims(
      iss: String,
      sub: String,
      aud: String,
      exp: Long,
      nonce: Option[String] = None,
      c_hash: Option[String] = None,
  ) derives JsonDecoder

  def spec = suite("Hybrid Flow (response_type=code id_token)")(

    test("full interactive login carries code and id_token in the fragment, with a matching nonce and c_hash") {
      val nonce = s"e2e-nonce-${UUID.randomUUID()}"
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          responseType = Some("code id_token"),
          nonce = Some(nonce),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        (code, idToken) <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf)
          .assertFragmentRedirect
        claims <- ZIO.fromEither(decodeJwtPayload(idToken).fromJson[HybridIdTokenClaims])
          .mapError(error => RuntimeException(s"Could not decode hybrid id_token claims [$error]"))
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        userinfo <- auth.userinfo(token.accessToken).success
      yield assertTrue(code.nonEmpty).label("fragment must carry a non-empty code") &&
        assertTrue(claims.nonce.contains(nonce))
          .label(s"id_token 'nonce' must equal the sent nonce '$nonce', got ${claims.nonce}") &&
        assertTrue(claims.sub == s.userId.toString)
          .label(s"id_token 'sub' must equal registered userId ${s.userId}, got ${claims.sub}") &&
        assertTrue(claims.aud == s.clientId).label(s"id_token 'aud' must equal clientId ${s.clientId}") &&
        assertTrue(claims.exp > Instant.now.getEpochSecond).label("id_token 'exp' must be in the future") &&
        assertTrue(claims.c_hash.contains(expectedCHash(code)))
          .label(s"id_token 'c_hash' must be the left-half SHA-256 hash of the fragment code, got ${claims.c_hash}") &&
        assertTrue(userinfo.sub == s.userId)
          .label("the code exchanged at /token must resolve to the same 'sub' as the fragment id_token")
    },

    test("hybrid without nonce is rejected with invalid_request in the fragment") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          responseType = Some("code id_token"),
        ).assertFragmentErrorRedirect("invalid_request")
      yield assertCompletes
    },

    test("a protocol error during a hybrid request is returned in the fragment, never the query") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          responseType = Some("code id_token"),
          omitCodeChallenge = true,
        ).assertFragmentErrorRedirect("invalid_request")
      yield assertCompletes
    },

  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

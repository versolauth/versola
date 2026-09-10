package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.test.*

/** RFC 7009 Token Revocation at the endpoint level: client authentication, cross-client token
  * ownership, and the always-200 contract for tokens that were never valid to begin with. Effects
  * on the edge (what a revocation actually accomplishes) are covered by
  * [[versola.e2e.flows.revocation.TokenRevocationFlowSpec]]; this suite is about `/revoke` itself.
  */
object RevocationSpec extends E2ESpec:

  private def login(s: Flows.Setup, auth: OAuthClient): Task[TokenResult.Success] =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
      token <- auth.token(code, authorize.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
    yield token

  /** EmailOtp's client is the only one of the ad-hoc test clients registered with the
    * `offline_access` scope, so it is used wherever a refresh_token is needed. */
  private def loginWithRefreshToken(s: Flows.Setup, auth: OAuthClient): Task[TokenResult.Success] =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri), scope = "openid offline_access")
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, s.email.get, challenge.csrf)
      otpChallenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      code <- auth.submitOtp(cookie, "123456", otpChallenge.csrf).assertRedirect(auth, cookie)
      token <- auth.token(code, authorize.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
    yield token

  private def errorOf(response: zio.http.Response): Task[Option[String]] =
    response.body.asString.map(_.fromJson[Map[String, String]].toOption.flatMap(_.get("error")))

  def spec = suite("Token Revocation Endpoint")(
    test("revoking a valid access token with correct client credentials returns 200") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        token <- login(s, auth)
        response <- auth.revoke(token.accessToken, s.clientId, s.clientSecret)
      yield assertTrue(response.status == Status.Ok)
    },
    test("wrong client credentials at /revoke are rejected with invalid_client") {
      // RFC 7009 \u00a72.1: the server validates the client credentials first, and "if this validation
      // fails, the request is refused" -- unlike an unknown token, which is not an error (\u00a72.2).
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        token <- login(s, auth)
        response <- auth.revoke(token.accessToken, s.clientId, "wrong-secret-wrong-secret-wrong-secret")
        error <- errorOf(response)
      yield assertTrue(response.status == Status.Unauthorized) &&
        assertTrue(error.contains("invalid_client"))
    },
    test("revoking another client's access token is rejected with invalid_client") {
      // The second half of RFC 7009 \u00a72.1's validation: the credentials are valid, but the token was
      // not issued to this client, so RevocationService refuses before revoking anything.
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        (other, _) <- setup(Flows.Id.EmailOtp) // a different flow is a different client; `setup` memoizes one per flow
        token <- login(s, auth)
        response <- auth.revoke(token.accessToken, other.clientId, other.clientSecret)
        error <- errorOf(response)
      yield assertTrue(response.status == Status.Unauthorized) &&
        assertTrue(error.contains("invalid_client"))
    },
    test("revoking a refresh_token issued to a different client is rejected with invalid_client") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (other, _) <- setup(Flows.Id.LoginPassword)
        token <- loginWithRefreshToken(s, auth)
        refreshToken <- ZIO.fromOption(token.refreshToken).orElseFail(RuntimeException("expected a refresh_token"))
        response <- auth.revoke(refreshToken, other.clientId, other.clientSecret)
        error <- errorOf(response)
      yield assertTrue(response.status == Status.Unauthorized) &&
        assertTrue(error.contains("invalid_client"))
    },
    test("revoking the same refresh_token twice is idempotent: the second call still returns 200") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        token <- loginWithRefreshToken(s, auth)
        refreshToken <- ZIO.fromOption(token.refreshToken).orElseFail(RuntimeException("expected a refresh_token"))
        first <- auth.revoke(refreshToken, s.clientId, s.clientSecret)
        second <- auth.revoke(refreshToken, s.clientId, s.clientSecret)
      yield assertTrue(first.status == Status.Ok) &&
        assertTrue(second.status == Status.Ok)
          .label("RFC 7009 §2.1: revoking an already-invalid token must not be treated as an error")
    },
    test("revoking a syntactically valid but unknown refresh_token returns 200, per RFC 7009 §2.1") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.revoke("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", s.clientId, s.clientSecret)
      yield assertTrue(response.status == Status.Ok)
    },
    test("an unparseable JWT-shaped access token is answered with 200, not surfaced as an error") {
      // A token whose signature does not verify is one this server did not issue, so it falls under
      // RFC 7009 \u00a72.2 alongside an unknown token -- and, deliberately, tells a caller probing with
      // forged tokens nothing about which of them the server would have recognized.
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        token <- login(s, auth)
        corrupted = token.accessToken.dropRight(4) + "AAAA"
        response <- auth.revoke(corrupted, s.clientId, s.clientSecret)
      yield assertTrue(response.status == Status.Ok)
    },
    test("a token value outside the base64url alphabet returns 200, since no client could hold it") {
      // RFC 7009 \u00a72.2: "invalid tokens do not cause an error response" -- the caller's goal, that
      // the token not be usable, already holds. /introspect answers the same input differently
      // (400 invalid_request, see IntrospectionSpec) because RFC 7662 has no such carve-out.
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.revoke("not-a-jwt-and-not-base64!!", s.clientId, s.clientSecret)
      yield assertTrue(response.status == Status.Ok)
    },
    test("wrong client credentials are rejected even when the token is a value no client could hold") {
      // RFC 7009 \u00a72.1's client-authentication requirement applies regardless of the token's own
      // disposition: a value no client could hold must not let wrong credentials slip through the
      // \u00a72.2 exemption above.
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.revoke("not-a-jwt-and-not-base64!!", s.clientId, "wrong-secret-wrong-secret-wrong-secret")
        error <- errorOf(response)
      yield assertTrue(response.status == Status.Unauthorized) &&
        assertTrue(error.contains("invalid_client"))
    },
    test("wrong client credentials are rejected even when the access token JWT does not verify") {
      // Same requirement as above, for the other unrecognized-token path: an unverifiable JWT is
      // exempt from RFC 7009 \u00a72.2 error reporting, but that must not bypass \u00a72.1's client
      // authentication.
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        token <- login(s, auth)
        corrupted = token.accessToken.dropRight(4) + "AAAA"
        response <- auth.revoke(corrupted, s.clientId, "wrong-secret-wrong-secret-wrong-secret")
        error <- errorOf(response)
      yield assertTrue(response.status == Status.Unauthorized) &&
        assertTrue(error.contains("invalid_client"))
    },
    test("token_type_hint is advisory only: an access token submitted with token_type_hint=refresh_token still revokes") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        token <- login(s, auth)
        response <- auth.revoke(token.accessToken, s.clientId, s.clientSecret, tokenTypeHint = Some("refresh_token"))
      yield assertTrue(response.status == Status.Ok)
          .label("RevocationController.tokenDecoder sniffs isJWT and never consults token_type_hint")
    },
    test("GET /revoke is rejected -- it is a POST-only endpoint") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.probe(zio.http.Method.GET, s"${auth.authBaseUrl}/revoke")
      yield assertTrue(response.status != Status.Ok)
    },
    test("a refresh_token submitted with token_type_hint=access_token still revokes (hint is advisory only)") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri), scope = "openid offline_access")
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, s.email.get, challenge.csrf)
        otpChallenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, "123456", otpChallenge.csrf).assertRedirect(auth, cookie)
        token <- auth.token(code, authorize.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
        refreshToken <- ZIO.fromOption(token.refreshToken).orElseFail(RuntimeException("expected a refresh_token"))
        response <- auth.revoke(refreshToken, s.clientId, s.clientSecret, tokenTypeHint = Some("access_token"))
      yield assertTrue(response.status == Status.Ok)
    },
    test("a request with no token parameter at all is rejected with invalid_request") {
      // A missing required parameter is a malformed request (RFC 6749 \u00a75.2, which RFC 7009 \u00a72.2.1
      // defers to), not a client-authentication failure and not a token the server failed to find.
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.revokeRaw(Map.empty, s.clientId, s.clientSecret)
        error <- errorOf(response)
      yield assertTrue(response.status == Status.BadRequest) &&
        assertTrue(error.contains("invalid_request"))
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

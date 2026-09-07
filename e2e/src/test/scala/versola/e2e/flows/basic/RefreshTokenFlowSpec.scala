package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.test.*

/** OAuth 2.0 refresh token grant (RFC 6749 §6) at `/token`.
  *
  * The grant rotates: [[versola.oauth.session.SessionRepository.createRefreshToken]] deletes the
  * presented token in the same transaction that inserts its successor, so every redemption both
  * hands back a new refresh token and kills the one that bought it.
  */
object RefreshTokenFlowSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  private val offlineScope = "openid email offline_access"

  /** A login through the shared email+OTP client, carrying the SSO_SESSION cookie the
    * conversation ended with so re-auth tests can continue from it.
    */
  private case class Login(tokens: TokenResult.Success, sessionCookie: String)

  private def login(
      s: Flows.Setup,
      auth: OAuthClient,
      scope: String = offlineScope,
      email: Option[String] = None,
  ): Task[Login] =
    for
      authorize <- auth.authorize(
        scope = scope,
        clientId = Some(s.clientId),
        redirectUri = Some(s.redirectUri),
      ).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, email.getOrElse(s.email.get), credential.csrf)
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      submit <- auth.submitOtp(cookie, fixedOtp, otp.csrf)
      code <- submit.assertRedirect
      sessionCookie <- ZIO.fromOption(submit.sessionCookie)
        .orElseFail(RuntimeException("No SSO_SESSION cookie after the OTP submission"))
      tokens <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
    yield Login(tokens, sessionCookie)

  private def refreshTokenOf(tokens: TokenResult.Success): Task[String] =
    ZIO.fromOption(tokens.refreshToken)
      .orElseFail(RuntimeException("Expected a refresh token for the offline_access scope"))

  /** RFC 6749 §5.2 error body, of which only the code is asserted on. */
  private case class OAuthError(error: String) derives JsonDecoder

  /** The `error` code of a rejected `/token` call, together with the status it came back with. */
  private def rejection(result: TokenResult): Task[(Status, String)] =
    result match
      case s: TokenResult.Success =>
        ZIO.fail(RuntimeException(s"Expected /token to reject the request, got ${s.response.status}"))
      case TokenResult.Failure(response, body) =>
        ZIO.fromEither(body.fromJson[OAuthError])
          .mapBoth(
            err => RuntimeException(s"Unparsable /token error body [$err]: $body"),
            parsed => response.status -> parsed.error,
          )

  /** A syntactically valid but unissued refresh token: the endpoint decodes it as base64url
    * before it ever looks it up, so an unparsable string would be rejected as a malformed
    * request rather than as an unknown grant.
    */
  private def unissuedRefreshToken: String =
    val bytes = Array.fill(32)(0.toByte)
    scala.util.Random.nextBytes(bytes)
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  def spec = suite("Refresh token grant")(

    test("redeeming a refresh token issues a new access token that works") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        first <- login(s, auth)
        refreshToken <- refreshTokenOf(first.tokens)
        refreshed <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
        rotated <- refreshTokenOf(refreshed)
        userinfo <- auth.userinfo(refreshed.accessToken).success
        // Access-token introspection is scoped to resources this client does not hold, so the
        // grant's liveness is read off the refresh token the rotation handed back.
        introspected <- auth.introspect(
          rotated,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
        spent <- auth.introspect(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
      yield assertTrue(refreshed.tokenType.toLowerCase == "bearer")
        .label("the refreshed token_type must be 'bearer'") &&
        assertTrue(userinfo.sub == s.userId)
          .label(s"the refreshed access token must resolve to ${s.userId} at /userinfo, got ${userinfo.sub}") &&
        assertTrue(introspected.active)
          .label("the rotated refresh token must introspect as active") &&
        assertTrue(!spent.active)
          .label("the refresh token that was spent must introspect as inactive")
    },

    test("the refreshed access token is a new one and keeps the original subject") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        first <- login(s, auth)
        refreshToken <- refreshTokenOf(first.tokens)
        original <- auth.userinfo(first.tokens.accessToken).success
        refreshed <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
        rotated <- refreshTokenOf(refreshed)
        userinfo <- auth.userinfo(refreshed.accessToken).success
      yield assertTrue(refreshed.accessToken != first.tokens.accessToken)
        .label("the refresh must mint a new access token, not hand back the old one") &&
        assertTrue(rotated != refreshToken)
          .label("the refresh must hand back a rotated refresh token, not the one presented") &&
        assertTrue(userinfo.sub == original.sub)
          .label(s"'sub' must survive the refresh: expected ${original.sub}, got ${userinfo.sub}")
    },

    test("a refresh token is single-use: the second redemption is rejected") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        first <- login(s, auth)
        refreshToken <- refreshTokenOf(first.tokens)
        refreshed <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
        rotated <- refreshTokenOf(refreshed)
        replay <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        )
        (status, error) <- rejection(replay)
        // The rotated token is the live one, so the replay must not have taken it down with it.
        again <- auth.refresh(
          rotated,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
      yield assertTrue(status == Status.BadRequest && error == "invalid_grant")
        .label(s"replaying a spent refresh token must be 400 invalid_grant, got $status/$error") &&
        assertTrue(again.accessToken.nonEmpty)
          .label("the rotated refresh token must still be redeemable after the replay was refused")
    },

    test("a refresh may narrow the granted scope") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        first <- login(s, auth)
        refreshToken <- refreshTokenOf(first.tokens)
        refreshed <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          scope = Some("openid offline_access"),
        ).success
        userinfo <- auth.userinfo(refreshed.accessToken).success
        granted = refreshed.scope.map(_.split(" ").toSet).getOrElse(Set.empty)
      yield assertTrue(granted == Set("openid", "offline_access"))
        .label(s"the narrowed grant must be echoed back, got ${refreshed.scope}") &&
        assertTrue(userinfo.email.isEmpty)
          .label("dropping 'email' from the scope must drop the email claim from /userinfo")
    },

    test("a refresh may not widen the granted scope") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        first <- login(s, auth)
        refreshToken <- refreshTokenOf(first.tokens)
        widened <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          scope = Some("openid email offline_access profile"),
        )
        (status, error) <- rejection(widened)
      yield assertTrue(status == Status.BadRequest && error == "invalid_scope")
        .label(s"asking for a scope the grant never carried must be 400 invalid_scope, got $status/$error")
    },

    test("a refresh token invalidated by re-auth without offline_access is rejected at /token") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        // A fresh user, so that killing this session cannot disturb the shared one.
        uid = java.util.UUID.randomUUID().toString.take(8)
        email = s"refresh-invalidated-$uid@example.test"
        _ <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()
        first <- login(s, auth, email = Some(email))
        refreshToken <- refreshTokenOf(first.tokens)

        // Re-auth WITHOUT offline_access takes the Invalidate path: the prior session and every
        // refresh token hanging off it expire.
        authorize <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          scope = Some("openid email"),
          prompt = Some("login"),
          sessionCookie = Some(first.sessionCookie),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, email, credential.csrf)
        otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, fixedOtp, otp.csrf).assertRedirect
        _ <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success

        result <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        )
        (status, error) <- rejection(result)
      yield assertTrue(status == Status.BadRequest && error == "invalid_grant")
        .label(s"an invalidated refresh token must be refused by /token, got $status/$error")
    },

    test("a refresh token revoked at /revoke is rejected at /token") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        first <- login(s, auth)
        refreshToken <- refreshTokenOf(first.tokens)
        revoked <- auth.revoke(refreshToken, s.clientId, s.clientSecret, tokenTypeHint = Some("refresh_token"))
        result <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        )
        (status, error) <- rejection(result)
      yield assertTrue(revoked.status == Status.Ok)
        .label(s"/revoke must answer 200, got ${revoked.status}") &&
        assertTrue(status == Status.BadRequest && error == "invalid_grant")
          .label(s"a revoked refresh token must be refused by /token, got $status/$error")
    },

    test("an unissued refresh token is rejected as an invalid grant") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        result <- auth.refresh(
          unissuedRefreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        )
        (status, error) <- rejection(result)
      yield assertTrue(status == Status.BadRequest && error == "invalid_grant")
        .label(s"a refresh token that was never issued must be 400 invalid_grant, got $status/$error")
    },

    test("another client's refresh token is rejected as an invalid grant") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (other, _) <- setup(Flows.Id.PhoneOtp)
        first <- login(s, auth)
        refreshToken <- refreshTokenOf(first.tokens)
        result <- auth.refresh(
          refreshToken,
          clientId = Some(other.clientId),
          clientSecret = Some(other.clientSecret),
        )
        (status, error) <- rejection(result)
      yield assertTrue(status == Status.BadRequest && error == "invalid_grant")
        .label(s"a refresh token belonging to another client must be 400 invalid_grant, got $status/$error")
    },

    test("a refresh with the wrong client secret is rejected as an invalid client") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        first <- login(s, auth)
        refreshToken <- refreshTokenOf(first.tokens)
        result <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret + "-wrong"),
        )
        (status, error) <- rejection(result)
        // The rejection must be about the credentials alone: the grant itself is untouched.
        after <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
      yield assertTrue(status == Status.Unauthorized && error == "invalid_client")
        .label(s"a wrong client secret must be 401 invalid_client, got $status/$error") &&
        assertTrue(after.accessToken.nonEmpty)
          .label("a failed client authentication must not spend the refresh token")
    },

    test("a login without offline_access yields no refresh token") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        first <- login(s, auth, scope = "openid email")
      yield assertTrue(first.tokens.refreshToken.isEmpty)
        .label(s"no offline_access means no refresh token, got ${first.tokens.refreshToken.isDefined}")
    },

  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)

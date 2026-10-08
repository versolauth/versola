package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.test.*

import java.util.UUID

/** Happy-path authorization code + PKCE flows. */
object BasicAuthFlowSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  def spec = suite("Basic Authorization Flow")(
    test("login + password: complete login flow") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorize(
          clientId = Some(s.clientId),
          redirectUri = Some(s.redirectUri),
        ).assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        csrf = challenge.csrf
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, csrf).assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(token.tokenType.toLowerCase == "bearer")
        .label("token_type must be 'bearer'") &&
        assertTrue(token.accessToken.nonEmpty)
          .label("access_token must not be empty")
    },
    // The conformance suite estimates a code's entropy from its encoded form and fails a 22-character
    // (16-byte) one at random, so the length that reaches the client over HTTP is what matters -- the
    // generator's unit test only proves what it asked the random source for.
    test("the authorization code issued over HTTP is 32 bytes, 43 base64url characters") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorize(
          clientId = Some(s.clientId),
          redirectUri = Some(s.redirectUri),
        ).assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, challenge.csrf)
          .assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(code.length == 43)
        .label(s"a 32-byte code is 43 base64url characters, got ${code.length}: '$code'") &&
        assertTrue(code.matches("[A-Za-z0-9_-]{43}"))
          .label("the code must be unpadded base64url") &&
        assertTrue(token.accessToken.nonEmpty)
          .label("a code of the new length must still redeem")
    },
    // RFC 6750 §2.2 / OIDC Core §5.3.1: the access token may travel in a form body. Only a `standard`
    // tenant allows it (FAPI 2.0 does not); the suite's tenant is on `standard`.
    test("UserInfo accepts the access token in a form body, and not in both places at once") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, challenge.csrf)
          .assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        viaBody <- auth.userinfoTokenInBody(token.accessToken)
        bothPlaces <- auth.userinfoTokenInBody(token.accessToken, alsoInHeader = true)
      yield assertTrue(viaBody.isInstanceOf[UserinfoResult.Success])
        .label(s"a token in the form body must be accepted for a standard tenant, got $viaBody") &&
        assertTrue(bothPlaces.response.status == zio.http.Status.Unauthorized)
          .label(s"a token in both the header and the body must be refused, got ${bothPlaces.response.status}")
    },
    test("otp + permanent password: complete otp flow") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        authorize <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        challenge1 <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        csrf1 = challenge1.csrf
        _ <- auth.submitEmail(authorize.conversationCookie.get, s.email.get, csrf1)
        challenge2 <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Otp)
        csrf2 = challenge2.csrf
        code <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, csrf2).assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        userinfo <- auth.userinfo(token.accessToken).success
      yield assertTrue(userinfo.sub == s.userId)
        .label(s"userinfo 'sub' must equal registered userId ${s.userId}, got ${userinfo.sub}") &&
        assertTrue(userinfo.email.contains(s.email.get))
          .label(s"userinfo 'email' must be ${s.email.get}")
    },
    test("unknown credential without registration rejects the fake OTP") {
      val unknownEmail = s"unknown-${UUID.randomUUID()}@example.test"
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        authorize <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge1 <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, unknownEmail, challenge1.csrf)
        challenge2 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(cookie, fixedOtp, challenge2.csrf)
        challenge3 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      yield assertTrue(challenge3.html.contains("Invalid verification code"))
        .label("an unknown credential must reject the fake OTP")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

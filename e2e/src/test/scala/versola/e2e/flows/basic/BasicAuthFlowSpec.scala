package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.test.*

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
        _ <- auth.getChallenge(authorize.conversationCookie).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(authorize.conversationCookie, s.login.get, s.password).assertRedirect(auth, authorize.conversationCookie)
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
    test("otp + permanent password: complete otp flow") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        authorize <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        _ <- auth.getChallenge(authorize.conversationCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(authorize.conversationCookie, s.email.get)
        _ <- auth.getChallenge(authorize.conversationCookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(authorize.conversationCookie, fixedOtp).assertRedirect(auth, authorize.conversationCookie)
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
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

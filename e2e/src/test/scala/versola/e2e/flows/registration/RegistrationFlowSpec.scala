package versola.e2e.flows.registration

import versola.e2e.support.{*, given}
import zio.*
import zio.test.*

import java.util.UUID

/** Self-service registration started from the credential card. */
object RegistrationFlowSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  private def unusedPhone: String =
    val uid = UUID.randomUUID()
    f"+49151${uid.getLeastSignificantBits.abs % 100_000_000L}%08d"

  def spec = suite("Registration Flow")(
    test("credential card uses the normal phone submission when registration is enabled") {
      val phoneCredentialConfig = "\"primaryCredentials\"\\s*:\\s*\\[\\s*\"phone\"\\s*\\]".r
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
      yield assertTrue(
        phoneCredentialConfig.findFirstIn(challenge.html).nonEmpty,
      ).label("credential card must include the phone credential field")
    },
    test("a credential that does not match the registration flow is denied") {
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, s"wrong-${UUID.randomUUID().toString.take(8)}@example.test", challenge.csrf)
        denied <- auth.getChallenge(cookie).assertStep(ConversationStep.AccessDenied)
      yield assertTrue(denied.step.contains(ConversationStep.AccessDenied))
    },
    test("a failed OTP does not reserve the credential") {
      val phone = unusedPhone
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        abandoned <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        abandonedCookie = abandoned.conversationCookie.get
        c1 <- auth.getChallenge(abandonedCookie).assertStep(ConversationStep.Credential)
        beforeOtp <- auth.userExistsByPhone(phone)
        _ <- auth.submitPhone(abandonedCookie, phone, c1.csrf)
        c2 <- auth.getChallenge(abandonedCookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(abandonedCookie, "000000", c2.csrf)
        _ <- auth.getChallenge(abandonedCookie).assertStep(ConversationStep.Otp)
        afterFailedOtp <- auth.userExistsByPhone(phone)

        retried <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        retriedCookie = retried.conversationCookie.get
        c3 <- auth.getChallenge(retriedCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(retriedCookie, phone, c3.csrf)
        c4 <- auth.getChallenge(retriedCookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(retriedCookie, fixedOtp, c4.csrf)
        c5 <- auth.getChallenge(retriedCookie).assertStep(ConversationStep.SetPassword)
      yield assertTrue(
        beforeOtp == false,
        afterFailedOtp == false,
        c5.step.contains(ConversationStep.SetPassword),
      )
        .label("an unverified attempt must not create or reserve the account")
    },
    test("a new phone number registers, sets a password, and completes the flow") {
      val phone = unusedPhone
      val password = s"Pass-${UUID.randomUUID().toString.take(8)}-1!"
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge1 <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        beforeOtp <- auth.userExistsByPhone(phone)
        _ <- auth.submitPhone(cookie, phone, challenge1.csrf)
        challenge2 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(cookie, fixedOtp, challenge2.csrf)
        afterOtp <- auth.awaitUserByPhone(phone)
        // The registration flow continues past the OTP rather than completing the sign-in.
        challenge3 <- auth.getChallenge(cookie).assertStep(ConversationStep.SetPassword)
        code <- auth.submitSetPassword(cookie, password, challenge3.csrf).assertRedirect(auth, cookie)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        userinfo <- auth.userinfo(token.accessToken).success
        centralId <- auth.findUserIdByPhone(phone)
      yield assertTrue(
        !beforeOtp,
        afterOtp,
        token.accessToken.nonEmpty,
        centralId.contains(userinfo.sub),
      )
        .label("registration must end in a usable access token whose id matches Central's index")
    },
    test("a new email address registers, sets a password, and completes the flow") {
      val password = s"Pass-${UUID.randomUUID().toString.take(8)}-1!"
      for
        (s, auth) <- setup(Flows.Id.EmailRegistration)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge1 <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, s.email.get, challenge1.csrf)
        challenge2 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(cookie, fixedOtp, challenge2.csrf)
        challenge3 <- auth.getChallenge(cookie).assertStep(ConversationStep.SetPassword)
        code <- auth.submitSetPassword(cookie, password, challenge3.csrf).assertRedirect(auth, cookie)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(token.accessToken.nonEmpty)
        .label("email registration must end in a usable access token")
    },
    test("the registered account signs in afterwards with the password it chose") {
      val phone = unusedPhone
      val password = s"Pass-${UUID.randomUUID().toString.take(8)}-1!"
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        // Register first, so the account exists for the sign-in below.
        first <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        firstCookie = first.conversationCookie.get
        c1 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(firstCookie, phone, c1.csrf)
        c2 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(firstCookie, fixedOtp, c2.csrf)
        c3 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.SetPassword)
        _ <- auth.submitSetPassword(firstCookie, password, c3.csrf).assertRedirect(auth, firstCookie)

        second <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        secondCookie = second.conversationCookie.get
        c4 <- auth.getChallenge(secondCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(secondCookie, phone, c4.csrf)
        c5 <- auth.getChallenge(secondCookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(secondCookie, fixedOtp, c5.csrf).assertRedirect(auth, secondCookie)
        token <- auth.token(
          code,
          second.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(token.accessToken.nonEmpty)
        .label("the registered account must be able to sign in")
    },
    test("registering a number that already exists is indistinguishable from signing in") {
      val phone = unusedPhone
      val password = s"Pass-${UUID.randomUUID().toString.take(8)}-1!"
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        first <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        firstCookie = first.conversationCookie.get
        c1 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(firstCookie, phone, c1.csrf)
        c2 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(firstCookie, fixedOtp, c2.csrf)
        c3 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.SetPassword)
        _ <- auth.submitSetPassword(firstCookie, password, c3.csrf).assertRedirect(auth, firstCookie)

        // Registering the same number again must not reveal that it is taken: the user is
        // silently moved to the ordinary OTP sign-in and never sees the set-password step.
        second <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        secondCookie = second.conversationCookie.get
        c4 <- auth.getChallenge(secondCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(secondCookie, phone, c4.csrf)
        c5 <- auth.getChallenge(secondCookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(secondCookie, fixedOtp, c5.csrf).assertRedirect(auth, secondCookie)
        token <- auth.token(
          code,
          second.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(token.accessToken.nonEmpty)
        .label("a taken number must complete as a normal sign-in")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

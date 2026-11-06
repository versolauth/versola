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
    test("registration asks for a password when the client's sign-in requires one") {
      val phone = unusedPhone
      val password = s"Pass-${UUID.randomUUID().toString.take(8)}-1!"
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistrationWithPassword)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        c1 <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(cookie, phone, c1.csrf)
        c2 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(cookie, fixedOtp, c2.csrf)
        // The client's sign-in has a password factor, so the new account is given a password now.
        c3 <- auth.getChallenge(cookie).assertStep(ConversationStep.SetPassword)
        code <- auth.submitSetPassword(cookie, password, c3.csrf).assertRedirect(auth, cookie)
      yield assertTrue(code.nonEmpty)
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
        code <- auth.submitOtp(retriedCookie, fixedOtp, c4.csrf).assertRedirect(auth, retriedCookie)
      yield assertTrue(
        beforeOtp == false,
        afterFailedOtp == false,
        code.nonEmpty,
      )
        .label("an unverified attempt must not create or reserve the account")
    },
    test("a new phone number registers and completes the flow") {
      val phone = unusedPhone
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge1 <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        beforeOtp <- auth.userExistsByPhone(phone)
        _ <- auth.submitPhone(cookie, phone, challenge1.csrf)
        challenge2 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        // The tenant's registration is the one-time code: the account is created and the flow completes.
        code <- auth.submitOtp(cookie, fixedOtp, challenge2.csrf).assertRedirect(auth, cookie)
        afterOtp <- auth.awaitUserByPhone(phone)
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
    test("a new email address registers and completes the flow") {
      for
        (s, auth) <- setup(Flows.Id.EmailRegistration)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge1 <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, s.email.get, challenge1.csrf)
        challenge2 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, fixedOtp, challenge2.csrf).assertRedirect(auth, cookie)
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
    test("the registered account signs in afterwards") {
      val phone = unusedPhone
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        // Register first, so the account exists for the sign-in below.
        first <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        firstCookie = first.conversationCookie.get
        c1 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(firstCookie, phone, c1.csrf)
        c2 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(firstCookie, fixedOtp, c2.csrf).assertRedirect(auth, firstCookie)

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
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        first <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        firstCookie = first.conversationCookie.get
        c1 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(firstCookie, phone, c1.csrf)
        c2 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(firstCookie, fixedOtp, c2.csrf).assertRedirect(auth, firstCookie)

        // Registering the same number again must not reveal that it is taken: the user is
        // silently moved to the ordinary OTP sign-in.
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
    test("a number registered with the trunk prefix signs in with its canonical E.164 form") {
      val phone = unusedPhone
      val withTrunkPrefix = phone.replaceFirst("^\\+49", "+490")
      for
        (s, auth) <- setup(Flows.Id.PhoneRegistration)
        first <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        firstCookie = first.conversationCookie.get
        c1 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitPhone(firstCookie, withTrunkPrefix, c1.csrf)
        c2 <- auth.getChallenge(firstCookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(firstCookie, fixedOtp, c2.csrf).assertRedirect(auth, firstCookie)
        registeredId <- auth.findUserIdByPhone(phone)

        // The canonical form finds the account: OTP completes the sign-in.
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
        userinfo <- auth.userinfo(token.accessToken).success
      yield assertTrue(
        registeredId.contains(userinfo.sub),
      ).label("both forms of one number must resolve to the account stored under the canonical form")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

package versola.e2e.flows.stepup

import versola.e2e.support.{*, given}
import zio.*

import zio.test.*

/** Step-up / session-aware authorization tests, including `acr_values` handling.
  *
  * Session re-auth scenarios:
  *   - Silent re-auth  — session satisfies all required factors → immediate code redirect
  *   - prompt=login    — forces a new challenge even with a valid session
  *   - max_age=0       — session is always "too old" → forces re-auth
  *
  * ACR scenarios (vocabulary configured by [[Flows.layer]]):
  *   - [[Acr.OtpLevel]]      ("otp-level")      → requires otp factor
  *   - [[Acr.PasswordLevel]] ("password-level") → requires password factor
  */
object StepUpFlowSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  /** Complete the email+OTP flow and return the SSO_SESSION cookie value. */
  private def completeOtpAuth(s: Flows.Setup, auth: OAuthClient): Task[String] =
    for
      authorize <- auth.authorize(
        scope = "openid",
        clientId = Some(s.clientId),
        redirectUri = Some(s.redirectUri),
      ).assertChallengeRedirect
      challenge1 <- auth.getChallenge(authorize.conversationCookie.get)
      csrf1 = challenge1.csrf
      _ <- auth.submitEmail(authorize.conversationCookie.get, s.email.get, csrf1)
      challenge2 <- auth.getChallenge(authorize.conversationCookie.get)
      csrf2 = challenge2.csrf
      submit <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, csrf2)
      sessionCookie <- ZIO.fromOption(submit.sessionCookie)
        .orElseFail(RuntimeException("No SSO_SESSION cookie in final submission response"))
    yield sessionCookie

  /** Complete the phone+OTP flow and return the SSO_SESSION cookie value. */
  private def completePhoneOtpAuth(s: Flows.Setup, auth: OAuthClient): Task[String] =
    for
      authorize <- auth.authorize(
        scope = "openid",
        clientId = Some(s.clientId),
        redirectUri = Some(s.redirectUri),
      ).assertChallengeRedirect
      challenge1 <- auth.getChallenge(authorize.conversationCookie.get)
      csrf1 = challenge1.csrf
      _ <- auth.submitPhone(authorize.conversationCookie.get, s.phone.get, csrf1)
      challenge2 <- auth.getChallenge(authorize.conversationCookie.get)
      csrf2 = challenge2.csrf
      submit <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, csrf2)
      sessionCookie <- ZIO.fromOption(submit.sessionCookie)
        .orElseFail(RuntimeException("No SSO_SESSION cookie in final submission response"))
    yield sessionCookie

  def spec = suite("Step-up authorization")(

    suite("session re-auth")(

      test("silent re-authorize: session with all factors satisfied returns code directly") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          code <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
          ).assertCodeRedirect
        yield assertTrue(code.nonEmpty).label("code must not be empty")
      },

      test("prompt=login with valid session forces new challenge") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            prompt = Some("login"),
          ).assertChallengeRedirect
        yield assertCompletes
      },

      test("known email user advances directly to OTP on prompt=login") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          authorize <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            prompt = Some("login"),
          ).assertChallengeRedirect
          challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Otp)
          code <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, challenge.csrf)
            .assertRedirect(auth, authorize.conversationCookie.get)
        yield assertTrue(code.nonEmpty).label("code must not be empty")
      },

      test("known phone user advances directly to OTP on prompt=login") {
        for
          (s, auth) <- setup(Flows.Id.PhoneOtp)
          sessionCookie <- completePhoneOtpAuth(s, auth)
          authorize <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            prompt = Some("login"),
          ).assertChallengeRedirect
          challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Otp)
          code <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, challenge.csrf)
            .assertRedirect(auth, authorize.conversationCookie.get)
        yield assertTrue(code.nonEmpty).label("code must not be empty")
      },

      test("max_age=0 with valid session forces new challenge") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            maxAge = Some(0),
          ).assertChallengeRedirect
        yield assertCompletes
      },

    ),

    suite("acr_values")(

      test("session satisfying the requested ACR → silent code redirect") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          code <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            acrValues = Some(Acr.OtpLevel),
          ).assertCodeRedirect
        yield assertTrue(code.nonEmpty).label("code must not be empty")
      },

      test("session not satisfying ACR, factor not achievable → unmet_authentication_requirements redirect") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            acrValues = Some(Acr.PasskeyLevel),
          ).assertErrorRedirect("unmet_authentication_requirements")
        yield assertCompletes
      },

      test("session not satisfying ACR + prompt=none → login_required redirect") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            prompt = Some("none"),
            acrValues = Some(Acr.PasskeyLevel),
          ).assertErrorRedirect("login_required")
        yield assertCompletes
      },

      test("unknown ACR value not in vocabulary → unmet_authentication_requirements redirect") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            acrValues = Some("urn:unknown:acr:level99"),
          ).assertErrorRedirect("unmet_authentication_requirements")
        yield assertCompletes
      },
    ),

    suite("missing user (session / hint)")(

      test("session user deleted -> step-up fails with access_denied") {
        for
          // Use shared client config but a fresh per-test user to avoid cross-test interference.
          (s, auth) <- setup(Flows.Id.EmailOtp)
          uid = java.util.UUID.randomUUID().toString.take(8)
          email = s"del-stepup-$uid@example.test"
          userId <- auth.registerUser(email = Some(email))
          _ <- auth.flushUserOutbox()  // register user in auth before logging in
          authorize <- auth.authorize(scope = "openid", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
          challenge1 <- auth.getChallenge(authorize.conversationCookie.get)
          csrf1 = challenge1.csrf
          _ <- auth.submitEmail(authorize.conversationCookie.get, email, csrf1)
          challenge2 <- auth.getChallenge(authorize.conversationCookie.get)
          csrf2 = challenge2.csrf
          submit <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, csrf2)
          sessionCookie <- ZIO.fromOption(submit.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie"))
          _ <- auth.deleteUser(userId)
          _ <- auth.flushUserOutbox()  // propagate deletion to auth before step-up
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            acrValues = Some(Acr.PasskeyLevel),
          ).assertErrorRedirect("access_denied")
        yield assertCompletes
      },

      test("session user deleted -> max_age=0 fails with access_denied") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          uid = java.util.UUID.randomUUID().toString.take(8)
          email = s"del-maxage-$uid@example.test"
          userId <- auth.registerUser(email = Some(email))
          _ <- auth.flushUserOutbox()
          authorize <- auth.authorize(scope = "openid", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
          challenge1 <- auth.getChallenge(authorize.conversationCookie.get)
          csrf1 = challenge1.csrf
          _ <- auth.submitEmail(authorize.conversationCookie.get, email, csrf1)
          challenge2 <- auth.getChallenge(authorize.conversationCookie.get)
          csrf2 = challenge2.csrf
          submit <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, csrf2)
          sessionCookie <- ZIO.fromOption(submit.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie"))
          _ <- auth.deleteUser(userId)
          _ <- auth.flushUserOutbox()
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            sessionCookie = Some(sessionCookie),
            maxAge = Some(0),
          ).assertErrorRedirect("access_denied")
        yield assertCompletes
      },
      test("hint user deleted (no session) -> fall back to login prompt") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          uid = java.util.UUID.randomUUID().toString.take(8)
          email = s"del-hint-$uid@example.test"
          userId <- auth.registerUser(email = Some(email))
          _ <- auth.flushUserOutbox()  // register in auth before getting id_token
          authorize <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
          challenge1 <- auth.getChallenge(authorize.conversationCookie.get)
          csrf1 = challenge1.csrf
          _ <- auth.submitEmail(authorize.conversationCookie.get, email, csrf1)
          challenge2 <- auth.getChallenge(authorize.conversationCookie.get)
          csrf2 = challenge2.csrf
          code <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, csrf2).assertRedirect
          token <- auth.token(
            code,
            authorize.verifier,
            clientId = Some(s.clientId),
            clientSecret = Some(s.clientSecret),
            redirectUri = Some(s.redirectUri),
          ).success
          idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("Missing id_token"))
          _ <- auth.deleteUser(userId)
          _ <- auth.flushUserOutbox()  // propagate deletion to auth before hint authorize
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            idTokenHint = Some(idToken),
          ).assertChallengeRedirect
        yield assertCompletes
      }
    ),

    suite("id_token_hint (verified identity)")(

      test("id_token_hint (user exists, no session) -> skip credential entry, go straight to OTP") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          uid = java.util.UUID.randomUUID().toString.take(8)
          email = s"hint-skip-$uid@example.test"
          _ <- auth.registerUser(email = Some(email))
          _ <- auth.flushUserOutbox()
          // 1. Get an id_token for this user
          authorize1 <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
          challenge1 <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf1 = challenge1.csrf
          _ <- auth.submitEmail(authorize1.conversationCookie.get, email, csrf1)
          challenge2 <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf2 = challenge2.csrf
          code1 <- auth.submitOtp(authorize1.conversationCookie.get, fixedOtp, csrf2).assertRedirect
          token1 <- auth.token(code1, authorize1.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
          idToken <- ZIO.fromOption(token1.idToken).orElseFail(RuntimeException("Missing id_token"))
          // 2. Start a NEW authorize flow with id_token_hint (no session) — must go straight to OTP, not credential
          result <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            idTokenHint = Some(idToken),
          ).assertChallengeRedirect
          cookie = result.conversationCookie.get
          // 3. Fetch challenge form and verify it's the OTP step, NOT the credential step
          challengePage <- auth.getChallenge(cookie)
          _ <- challengePage.assertStep(ConversationStep.Otp)
        yield assertCompletes
      },

      test("id_token_hint (user exists, no session, phone) -> skip credential entry, go straight to OTP") {
        for
          (s, auth) <- setup(Flows.Id.PhoneOtp)
          phone = f"+49151${java.util.UUID.randomUUID().getLeastSignificantBits.abs % 100_000_000L}%08d"
          _ <- auth.registerUser(phone = Some(phone))
          _ <- auth.flushUserOutbox()
          // 1. Get an id_token for this user
          authorize1 <- auth.authorize(scope = "openid", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
          challenge1 <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf1 = challenge1.csrf
          _ <- auth.submitPhone(authorize1.conversationCookie.get, phone, csrf1)
          challenge2 <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf2 = challenge2.csrf
          code1 <- auth.submitOtp(authorize1.conversationCookie.get, fixedOtp, csrf2).assertRedirect
          token1 <- auth.token(code1, authorize1.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
          idToken <- ZIO.fromOption(token1.idToken).orElseFail(RuntimeException("Missing id_token"))
          // 2. Start a NEW authorize flow with id_token_hint (no session) — must go straight to OTP, not credential
          result <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            idTokenHint = Some(idToken),
          ).assertChallengeRedirect
          cookie = result.conversationCookie.get
          // 3. Fetch challenge form and verify it's the OTP step, NOT the credential step
          challengePage <- auth.getChallenge(cookie)
          _ <- challengePage.assertStep(ConversationStep.Otp)
        yield assertCompletes
      },

      test("id_token_hint (user exists) -> submitting any phone is rejected with access_denied") {
        for
          (s, auth) <- setup(Flows.Id.PhoneOtp)
          phone = f"+49151${java.util.UUID.randomUUID().getLeastSignificantBits.abs % 100_000_000L}%08d"
          otherPhone = f"+49152${java.util.UUID.randomUUID().getLeastSignificantBits.abs % 100_000_000L}%08d"
          _ <- auth.registerUser(phone = Some(phone))
          _ <- auth.registerUser(phone = Some(otherPhone))
          _ <- auth.flushUserOutbox()
          // 1. Get an id_token for the real user
          authorize1 <- auth.authorize(scope = "openid", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
          challenge1 <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf1 = challenge1.csrf
          _ <- auth.submitPhone(authorize1.conversationCookie.get, phone, csrf1)
          challenge2 <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf2 = challenge2.csrf
          code1 <- auth.submitOtp(authorize1.conversationCookie.get, fixedOtp, csrf2).assertRedirect
          token1 <- auth.token(code1, authorize1.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
          idToken <- ZIO.fromOption(token1.idToken).orElseFail(RuntimeException("Missing id_token"))
          // 2. Start a flow with id_token_hint — identity is locked (userId set in conversation)
          result <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            idTokenHint = Some(idToken),
          ).assertChallengeRedirect
          cookie = result.conversationCookie.get
          // 3. Manually POST to /challenge/phone with a different phone — must be rejected
          attackChallenge <- auth.getChallenge(cookie)
          attackCsrf = attackChallenge.csrf
          _ <- auth.submitPhone(cookie, otherPhone, attackCsrf)
          // 4. Fetch the challenge — must show AccessDenied, not OTP
          challengePage <- auth.getChallenge(cookie)
          _ <- challengePage.assertStep(ConversationStep.AccessDenied)
        yield assertCompletes
      },

      test("id_token_hint (user exists) -> submitting any email is rejected with access_denied") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          uid = java.util.UUID.randomUUID().toString.take(8)
          email = s"hint-locked-$uid@example.test"
          otherEmail = s"hint-attacker-$uid@example.test"
          _ <- auth.registerUser(email = Some(email))
          _ <- auth.registerUser(email = Some(otherEmail))
          _ <- auth.flushUserOutbox()
          // 1. Get an id_token for the real user
          authorize1 <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
          challenge1 <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf1 = challenge1.csrf
          _ <- auth.submitEmail(authorize1.conversationCookie.get, email, csrf1)
          challenge2 <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf2 = challenge2.csrf
          code1 <- auth.submitOtp(authorize1.conversationCookie.get, fixedOtp, csrf2).assertRedirect
          token1 <- auth.token(code1, authorize1.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
          idToken <- ZIO.fromOption(token1.idToken).orElseFail(RuntimeException("Missing id_token"))
          // 2. Start a flow with id_token_hint — identity is locked (userId set in conversation)
          result <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            idTokenHint = Some(idToken),
          ).assertChallengeRedirect
          cookie = result.conversationCookie.get
          // 3. Manually POST to /challenge/email with a different email — must be rejected
          attackChallenge <- auth.getChallenge(cookie)
          attackCsrf = attackChallenge.csrf
          _ <- auth.submitEmail(cookie, otherEmail, attackCsrf)
          // 4. Fetch the challenge — must show AccessDenied, not OTP
          challengePage <- auth.getChallenge(cookie)
          _ <- challengePage.assertStep(ConversationStep.AccessDenied)
        yield assertCompletes
      }
    ),

    suite("session rotation and token invalidation")(

      test("prompt=login without offline_access invalidates old refresh token (Invalidate path)") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          // 1. First auth — issue a refresh token
          authorize1 <- auth.authorize(
            scope = "openid email offline_access",
            clientId = Some(s.clientId),
            redirectUri = Some(s.redirectUri),
          ).assertChallengeRedirect
          challenge1a <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf1a = challenge1a.csrf
          _ <- auth.submitEmail(authorize1.conversationCookie.get, s.email.get, csrf1a)
          challenge1b <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf1b = challenge1b.csrf
          submit1 <- auth.submitOtp(authorize1.conversationCookie.get, fixedOtp, csrf1b)
          code1 <- submit1.assertRedirect
          sessionCookie1 <- ZIO.fromOption(submit1.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie 1"))
          token1 <- auth.token(code1, authorize1.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
          refreshToken1 <- ZIO.fromOption(token1.refreshToken).orElseFail(RuntimeException("Missing first refresh token"))

          // 2. Re-auth WITHOUT offline_access → Invalidate path (new session, old RT killed)
          authorize2 <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            scope = Some("openid email"),
            prompt = Some("login"),
            sessionCookie = Some(sessionCookie1),
          ).assertChallengeRedirect
          challenge2a <- auth.getChallenge(authorize2.conversationCookie.get)
          csrf2a = challenge2a.csrf
          _ <- auth.submitEmail(authorize2.conversationCookie.get, s.email.get, csrf2a)
          challenge2b <- auth.getChallenge(authorize2.conversationCookie.get)
          csrf2b = challenge2b.csrf
          submit2 <- auth.submitOtp(authorize2.conversationCookie.get, fixedOtp, csrf2b)
          code2 <- submit2.assertRedirect
          sessionCookie2 <- ZIO.fromOption(submit2.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie 2"))
          _ <- auth.token(code2, authorize2.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success

          // 3. Old refresh token must be dead
          introspectResult <- auth.introspect(refreshToken1, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret)).success
        yield assertTrue(
          sessionCookie1 != sessionCookie2,
          !introspectResult.active,
        )
      },

      test("prompt=login with offline_access migrates old refresh token to new session (MigrateTokens path)") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          // 1. First auth — issue a refresh token
          authorize1 <- auth.authorize(
            scope = "openid email offline_access",
            clientId = Some(s.clientId),
            redirectUri = Some(s.redirectUri),
          ).assertChallengeRedirect
          challenge1a <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf1a = challenge1a.csrf
          _ <- auth.submitEmail(authorize1.conversationCookie.get, s.email.get, csrf1a)
          challenge1b <- auth.getChallenge(authorize1.conversationCookie.get)
          csrf1b = challenge1b.csrf
          submit1 <- auth.submitOtp(authorize1.conversationCookie.get, fixedOtp, csrf1b)
          code1 <- submit1.assertRedirect
          sessionCookie1 <- ZIO.fromOption(submit1.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie 1"))
          token1 <- auth.token(code1, authorize1.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success
          refreshToken1 <- ZIO.fromOption(token1.refreshToken).orElseFail(RuntimeException("Missing first refresh token"))

          // 2. Re-auth WITH offline_access → MigrateTokens path (new session, old RT re-parented)
          authorize2 <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            scope = Some("openid email offline_access"),
            prompt = Some("login"),
            sessionCookie = Some(sessionCookie1),
          ).assertChallengeRedirect
          challenge2a <- auth.getChallenge(authorize2.conversationCookie.get)
          csrf2a = challenge2a.csrf
          _ <- auth.submitEmail(authorize2.conversationCookie.get, s.email.get, csrf2a)
          challenge2b <- auth.getChallenge(authorize2.conversationCookie.get)
          csrf2b = challenge2b.csrf
          submit2 <- auth.submitOtp(authorize2.conversationCookie.get, fixedOtp, csrf2b)
          code2 <- submit2.assertRedirect
          sessionCookie2 <- ZIO.fromOption(submit2.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie 2"))
          _ <- auth.token(code2, authorize2.verifier, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret), redirectUri = Some(s.redirectUri)).success

          // 3. Old refresh token must still be active (migrated to new session)
          introspectResult <- auth.introspect(refreshToken1, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret)).success
        yield assertTrue(
          sessionCookie1 != sessionCookie2,
          introspectResult.active,
        )
      },
    ),

  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)

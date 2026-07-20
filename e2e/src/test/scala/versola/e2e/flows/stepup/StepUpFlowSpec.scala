package versola.e2e.flows.stepup

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
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
      _ <- auth.submitEmail(authorize.conversationCookie, s.email.get)
      submit <- auth.submitOtp(authorize.conversationCookie, fixedOtp)
      sessionCookie <- ZIO.fromOption(submit.sessionCookie)
        .orElseFail(RuntimeException("No SSO_SESSION cookie in final submission response"))
    yield sessionCookie

  def spec = suite("Step-up authorization")(

    suite("session re-auth")(

      test("silent re-authorize: session with all factors satisfied returns code directly") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          (_, challenge) = PkceHelper.generate()
          code <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            codeChallenge = Some(challenge),
            sessionCookie = Some(sessionCookie),
          ).assertCodeRedirect
        yield assertTrue(code.nonEmpty).label("code must not be empty")
      },

      test("prompt=login with valid session forces new challenge") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          (_, challenge) = PkceHelper.generate()
          response <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            codeChallenge = Some(challenge),
            sessionCookie = Some(sessionCookie),
            prompt = Some("login"),
          )
        yield assertTrue(response.status == Status.SeeOther)
          .label("must redirect") &&
          assertTrue(response.location.contains("/challenge"))
            .label("prompt=login must redirect to /challenge even with valid session")
      },

      test("max_age=0 with valid session forces new challenge") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          (_, challenge) = PkceHelper.generate()
          response <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            codeChallenge = Some(challenge),
            sessionCookie = Some(sessionCookie),
            maxAge = Some(0),
          )
        yield assertTrue(response.status == Status.SeeOther)
          .label("must redirect") &&
          assertTrue(response.location.contains("/challenge"))
            .label("max_age=0 must redirect to /challenge even with valid session")
      },

    ),

    suite("acr_values")(

      test("session satisfying the requested ACR → silent code redirect") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          (_, challenge) = PkceHelper.generate()
          code <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            codeChallenge = Some(challenge),
            sessionCookie = Some(sessionCookie),
            acrValues = Some(Acr.OtpLevel),
          ).assertCodeRedirect
        yield assertTrue(code.nonEmpty).label("code must not be empty")
      },

      test("session not satisfying ACR, factor not achievable → unmet_authentication_requirements redirect") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          (_, challenge) = PkceHelper.generate()
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            codeChallenge = Some(challenge),
            sessionCookie = Some(sessionCookie),
            acrValues = Some(Acr.PasskeyLevel),
          ).assertErrorRedirect("unmet_authentication_requirements")
        yield assertCompletes
      },

      test("session not satisfying ACR + prompt=none → login_required redirect") {
        for
          (s, auth) <- setup(Flows.Id.EmailOtp)
          sessionCookie <- completeOtpAuth(s, auth)
          (_, challenge) = PkceHelper.generate()
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            codeChallenge = Some(challenge),
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
          (_, challenge) = PkceHelper.generate()
          _ <- auth.authorizeRaw(
            clientId = s.clientId,
            redirectUri = s.redirectUri,
            codeChallenge = Some(challenge),
            sessionCookie = Some(sessionCookie),
            acrValues = Some("urn:unknown:acr:level99"),
          ).assertErrorRedirect("unmet_authentication_requirements")
        yield assertCompletes
      },

    ),

  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)

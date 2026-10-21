package versola.e2e.flows.stepup

import versola.e2e.support.{*, given}
import zio.*
import zio.json.*
import zio.test.*

/** The built-in [[Acr.Sensitive]] class asks each user for the strongest factor they have: a passkey when
  * they have one, else what the client's flow can ask of them. A tenant does not list it in its vocabulary,
  * so nothing here is configured beyond what [[Flows.layer]] already does.
  */
object SensitiveStepUpSpec extends E2ESpec:

  /** The origin the seeded challenge settings trust for passkeys (see `upsertChallengeSettings`). */
  private val passkeyOrigin = "http://localhost:3000"

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  /** `session` is the `SSO_SESSION` the password sign-in left behind, which has passed a password and nothing else. */
  private case class Enrolled(credential: TestAuthenticator.Credential, idToken: String, session: String)

  private def claimsOf(idToken: String): Task[String] =
    ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(idToken.split('.')(1)), java.nio.charset.StandardCharsets.UTF_8))

  /** Signs in with login + password, enrolls a passkey, and returns it with the id token of that sign-in. */
  private def enroll(s: Flows.Setup, auth: OAuthClient): Task[Enrolled] =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      submit <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf)
      code <- submit.assertRedirect
      session <- ZIO.fromOption(submit.sessionCookie).orElseFail(RuntimeException("no SSO_SESSION after the password sign-in"))
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
      idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("token response carries no id_token"))
      claims <- claimsOf(idToken)
      sid <- ZIO.fromEither(claims.fromJson[Sid]).mapError(error => RuntimeException(s"no sid [$error]: $claims")).map(_.sid)
      caller = AccountCaller(s.userId, s.clientId, sid)
      started <- auth.startPasskeyEnrollment(caller)
      ceremony <- ZIO.fromEither(started.body.fromJson[StartEnrollment])
        .mapError(error => RuntimeException(s"Unexpected enrollment start response [$error]: ${started.body}"))
      credential <- TestAuthenticator.create(ceremony.publicKeyOptions, passkeyOrigin)
      _ <- auth.finishPasskeyEnrollment(caller, ceremony.ticket, credential.responseJson, "E2E Passkey")
    yield Enrolled(credential, idToken, session)

  /** Starts the step-up for the user behind `idToken`, as the app does, and returns the conversation cookie. */
  private def startStepUp(s: Flows.Setup, auth: OAuthClient, idToken: String): Task[(String, ChallengeResult, String)] =
    for
      result <- auth.authorizeRaw(
        clientId = s.clientId,
        redirectUri = s.redirectUri,
        prompt = Some("login"),
        acrValues = Some(Acr.Sensitive),
        idTokenHint = Some(idToken),
      ).assertChallengeRedirect
      cookie = result.conversationCookie.get
      challenge <- auth.getChallenge(cookie)
    yield (cookie, challenge, result.verifier)

  /** Completes the email + one-time code sign-in and returns the `SSO_SESSION` cookie. */
  private def completeOtpAuth(s: Flows.Setup, auth: OAuthClient): Task[String] =
    for
      authorize <- auth.authorize(scope = "openid", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie)
      _ <- auth.submitEmail(cookie, s.email.get, credential.csrf)
      otp <- auth.getChallenge(cookie)
      submit <- auth.submitOtp(cookie, fixedOtp, otp.csrf)
      _ <- ZIO.succeed(submit).assertRedirect(auth, cookie)
      session <- ZIO.fromOption(submit.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie in final submission response"))
    yield session

  /** A client and user of their own. The shared setup has one user, which holds every passkey the other tests enrolled. */
  private def freshUser: ZIO[Flows.Setups, Throwable, (Flows.Setup, OAuthClient)] =
    ZIO.serviceWithZIO[Flows.Setups]: setups =>
      for
        s <- Flows.setupPasskeyLogin().provide(ZLayer.succeed(setups.client))
        _ <- setups.client.flushUserOutbox()
        _ <- setups.client.syncConfiguration()
      yield (s, setups.client)

  def spec = suite("Built-in sensitive step-up")(
    test("a user who has a passkey is asked for it at once, and a passkey of theirs completes it") {
      for
        (s, auth) <- freshUser
        enrolled <- enroll(s, auth)
        (cookie, challenge, verifier) <- startStepUp(s, auth, enrolled.idToken)
        options <- auth.getPasskeyOptions(cookie).flatMap(_.body.asString)
        response <- TestAuthenticator.get(enrolled.credential, options, passkeyOrigin, s.userId)
        code <- auth.submitPasskeyAssertion(cookie, response.toJson, challenge.csrf).assertRedirect
        token <- auth.token(
          code,
          verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        claims <- claimsOf(token.idToken.get)
      yield assertTrue(challenge.html.contains("\"passkeyOnly\":true")).label("no credential form, only the passkey step") &&
        assertTrue(claims.contains(s""""acr":"${Acr.Sensitive}"""")).label(s"acr claim in $claims") &&
        assertTrue(claims.contains("swk") || claims.contains("hwk")).label(s"amr claim in $claims")
    },
    test("a password cannot stand in for the passkey the user has") {
      for
        (s, auth) <- freshUser
        enrolled <- enroll(s, auth)
        (cookie, challenge, _) <- startStepUp(s, auth, enrolled.idToken)
        result <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf)
        noCode <- result.assertRedirect.exit
        again <- auth.getChallenge(cookie)
      yield assertTrue(noCode.isFailure).label("no authorization code") &&
        assertTrue(again.step.contains(ConversationStep.Credential), again.html.contains("\"passkeyOnly\":true"))
    },
    test("a user with no passkey whom the flow cannot ask for anything else is refused") {
      for
        (s, auth) <- freshUser
        // An id token of this user from a plain sign-in. Their flow takes the password inline on the
        // credential card, which a step-up for a known user skips, and no one-time code is reachable.
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("no id_token"))
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          prompt = Some("login"),
          acrValues = Some(Acr.Sensitive),
          idTokenHint = Some(idToken),
        ).assertErrorRedirect("unmet_authentication_requirements")
      yield assertCompletes
    },
    test("a session that passed only a password does not satisfy it for a user who has a passkey: the passkey is asked for") {
      for
        (s, auth) <- freshUser
        enrolled <- enroll(s, auth)
        started <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          sessionCookie = Some(enrolled.session),
          acrValues = Some(Acr.Sensitive),
        ).assertChallengeRedirect
        cookie = started.conversationCookie.get
        challenge <- auth.getChallenge(cookie)
        result <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf)
        noCode <- result.assertRedirect.exit
      yield assertTrue(challenge.html.contains("\"passkeyOnly\":true")).label("the passkey step, not a code") &&
        assertTrue(noCode.isFailure).label("the password the session already passed is no substitute")
    },
    test("a session that already passed the one-time code satisfies it for a user who has nothing stronger") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        session <- completeOtpAuth(s, auth)
        code <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          sessionCookie = Some(session),
          acrValues = Some(Acr.Sensitive),
        ).assertCodeRedirect
      yield assertTrue(code.nonEmpty).label("a code, without asking again: freshness is max_age's to enforce")
    },
    test("max_age asks for a fresh one-time code, and the token carries the class") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        session <- completeOtpAuth(s, auth)
        // What edge's challenge asks the app for (RFC 9470): the class and the age the session may have at most.
        started <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          sessionCookie = Some(session),
          maxAge = Some(0),
          acrValues = Some(Acr.Sensitive),
        ).assertChallengeRedirect
        cookie = started.conversationCookie.get
        otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, fixedOtp, otp.csrf).assertRedirect(auth, cookie)
        token <- auth.token(
          code,
          started.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        claims <- claimsOf(token.idToken.get)
      yield assertTrue(claims.contains(s""""acr":"${Acr.Sensitive}"""")).label(s"acr claim in $claims")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)

  private case class Sid(sid: String) derives zio.json.JsonDecoder

  private case class StartEnrollment(publicKeyOptions: String, ticket: String) derives zio.json.JsonDecoder

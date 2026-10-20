package versola.e2e.flows.stepup

import versola.e2e.support.{*, given}
import zio.*
import zio.json.*
import zio.test.*

/** A step-up whose ACR needs a passkey ([[Acr.PasskeyLevel]]) can only be completed with a passkey of the
  * user it was started for. Every other way a client or a caller might try to get a code out of it is
  * tried here, and none may produce one.
  */
object PasskeyStepUpSpec extends E2ESpec:

  /** The origin the seeded challenge settings trust for passkeys (see `upsertChallengeSettings`). */
  private val passkeyOrigin = "http://localhost:3000"

  private case class Enrolled(credential: TestAuthenticator.Credential, idToken: String)

  private def claimsOf(idToken: String): Task[String] =
    ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(idToken.split('.')(1)), java.nio.charset.StandardCharsets.UTF_8))

  /** Signs in with login + password, enrolls a passkey, and returns it with the id token of that sign-in. */
  private def enroll(s: Flows.Setup, auth: OAuthClient): Task[Enrolled] =
    for
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
      idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("token response carries no id_token"))
      claims <- claimsOf(idToken)
      sid <- ZIO.fromEither(claims.fromJson[Sid]).mapError(error => RuntimeException(s"no sid [$error]: $claims")).map(_.sid)
      caller = AccountCaller(s.userId, s.clientId, sid)
      started <- auth.startPasskeyEnrollment(caller)
      ceremony <- ZIO.fromEither(started.body.fromJson[StartEnrollment])
        .mapError(error => RuntimeException(s"Unexpected enrollment start response [$error]: ${started.body}"))
      credential <- TestAuthenticator.create(ceremony.publicKeyOptions, passkeyOrigin)
      _ <- auth.finishPasskeyEnrollment(caller, ceremony.ticket, credential.responseJson, "E2E Passkey")
    yield Enrolled(credential, idToken)

  /** Starts the step-up for the user behind `idToken`, as the app does, and returns the conversation cookie. */
  private def startStepUp(s: Flows.Setup, auth: OAuthClient, idToken: String): Task[(String, ChallengeResult, String)] =
    for
      result <- auth.authorizeRaw(
        clientId = s.clientId,
        redirectUri = s.redirectUri,
        prompt = Some("login"),
        acrValues = Some(Acr.PasskeyLevel),
        idTokenHint = Some(idToken),
      ).assertChallengeRedirect
      cookie = result.conversationCookie.get
      challenge <- auth.getChallenge(cookie)
    yield (cookie, challenge, result.verifier)

  /** A client and user of their own. The shared setup has one user, which holds every passkey the other tests enrolled. */
  private def freshUser: ZIO[Flows.Setups, Throwable, (Flows.Setup, OAuthClient)] =
    ZIO.serviceWithZIO[Flows.Setups]: setups =>
      for
        s <- Flows.setupPasskeyLogin().provide(ZLayer.succeed(setups.client))
        _ <- setups.client.flushUserOutbox()
        _ <- setups.client.syncConfiguration()
      yield (s, setups.client)

  def spec = suite("Passkey step-up")(
    test("asks for the passkey at once, with no credential form, and a passkey of the user completes it") {
      for
        (s, auth) <- setup(Flows.Id.PasskeyLogin)
        enrolled <- enroll(s, auth)
        (cookie, challenge, verifier) <- startStepUp(s, auth, enrolled.idToken)
        _ <- challenge.assertStep(ConversationStep.Credential)
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
        assertTrue(options.contains("allowCredentials")).label("the ceremony is restricted to the user's passkeys") &&
        assertTrue(claims.contains(s""""acr":"${Acr.PasskeyLevel}"""")).label(s"acr claim in $claims") &&
        assertTrue(claims.contains("swk") || claims.contains("hwk")).label(s"amr claim in $claims")
    },
    test("a password cannot stand in for the passkey: the conversation stays on the passkey step and yields no code") {
      for
        (s, auth) <- setup(Flows.Id.PasskeyLogin)
        enrolled <- enroll(s, auth)
        (cookie, challenge, _) <- startStepUp(s, auth, enrolled.idToken)
        result <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf)
        noCode <- result.assertRedirect.exit
        again <- auth.getChallenge(cookie)
      yield assertTrue(noCode.isFailure).label("no authorization code") &&
        assertTrue(again.step.contains(ConversationStep.Credential), again.html.contains("\"passkeyOnly\":true"))
    },
    test("a one-time code or a phone number cannot be submitted to leave the passkey step") {
      for
        (s, auth) <- setup(Flows.Id.PasskeyLogin)
        enrolled <- enroll(s, auth)
        (cookie, challenge, _) <- startStepUp(s, auth, enrolled.idToken)
        otp <- auth.submitOtp(cookie, "123456", challenge.csrf)
        otpCode <- otp.assertRedirect.exit
        phone <- auth.submitPhone(cookie, "+4915100000000", challenge.csrf)
        phoneCode <- phone.assertRedirect.exit
      yield assertTrue(otpCode.isFailure, phoneCode.isFailure)
    },
    test("a user with no passkey is refused, not offered a password or a code in its place") {
      for
        (s, auth) <- freshUser
        // An id token of this user from a plain sign-in; no passkey is enrolled.
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
          acrValues = Some(Acr.PasskeyLevel),
          idTokenHint = Some(idToken),
        ).assertErrorRedirect("unmet_authentication_requirements")
      yield assertCompletes
    },
    test("another user's passkey does not confirm, and the conversation is closed") {
      for
        (s, auth) <- freshUser
        enrolled <- enroll(s, auth)
        (other, _) <- freshUser
        theirs <- enroll(other, auth)
        (cookie, challenge, _) <- startStepUp(s, auth, enrolled.idToken)
        options <- auth.getPasskeyOptions(cookie).flatMap(_.body.asString)
        forged <- TestAuthenticator.get(theirs.credential, options, passkeyOrigin, other.userId)
        result <- auth.submitPasskeyAssertion(cookie, forged.toJson, challenge.csrf)
        noCode <- result.assertRedirect.exit
        after <- auth.getChallenge(cookie)
      yield assertTrue(noCode.isFailure).label("no authorization code") &&
        assertTrue(after.step.contains(ConversationStep.AccessDenied))
    },
    test("when the browser cannot produce a passkey the conversation is denied and yields no code") {
      for
        (s, auth) <- setup(Flows.Id.PasskeyLogin)
        enrolled <- enroll(s, auth)
        (cookie, challenge, _) <- startStepUp(s, auth, enrolled.idToken)
        result <- auth.submitPasskeyUnavailable(cookie, challenge.csrf)
        noCode <- result.assertRedirect.exit
        after <- auth.getChallenge(cookie)
      yield assertTrue(noCode.isFailure, after.step.contains(ConversationStep.AccessDenied))
    },
    test("'passkey unavailable' is refused on an ordinary sign-in step") {
      for
        (s, auth) <- setup(Flows.Id.PasskeyLogin)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        result <- auth.submitPasskeyUnavailable(cookie, challenge.csrf)
        after <- auth.getChallenge(cookie)
      yield assertTrue(after.step.contains(ConversationStep.Credential))
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)

  private case class Sid(sid: String) derives zio.json.JsonDecoder

  private case class StartEnrollment(publicKeyOptions: String, ticket: String) derives zio.json.JsonDecoder

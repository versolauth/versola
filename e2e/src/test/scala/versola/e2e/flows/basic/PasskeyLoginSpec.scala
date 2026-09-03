package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.test.*

/** Passkey login (discoverable-credential assertion) happy and unhappy paths, exercised
  * against a client whose auth flow offers a passkey primary alongside login+password.
  */
object PasskeyLoginSpec extends E2ESpec:

  /** The origin the seeded challenge settings trust for passkeys (see `upsertChallengeSettings`). */
  private val passkeyOrigin = "http://localhost:3000"

  def spec = suite("Passkey Login")(
    test("enroll then sign in with a passkey completes the authorization flow") {
      for
        (s, auth) <- setup(Flows.Id.PasskeyLogin)
        credential <- enrollPasskey(s, auth)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        optionsBody <- auth.getPasskeyOptions(cookie).flatMap(_.body.asString)
        response <- TestAuthenticator.get(credential, optionsBody, passkeyOrigin, s.userId)
        code <- auth.submitPasskeyAssertion(cookie, response.toJson, challenge.csrf).assertRedirect(auth, cookie)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        userinfo <- auth.userinfo(token.accessToken).success
      yield assertTrue(token.tokenType.toLowerCase == "bearer") &&
        assertTrue(userinfo.sub == s.userId)
          .label(s"userinfo 'sub' must equal registered userId ${s.userId}, got ${userinfo.sub}")
    },
    test("an assertion for an unenrolled user is rejected and re-renders the credential step") {
      for
        (s, auth) <- setup(Flows.Id.PasskeyLogin)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        optionsBody <- auth.getPasskeyOptions(cookie).flatMap(_.body.asString)
        stray <- TestAuthenticator.create(
          s"""{"publicKey":{"challenge":"AAAA","rp":{"id":"localhost"}}}""",
          passkeyOrigin,
        )
        response <- TestAuthenticator.get(stray, optionsBody, passkeyOrigin, s.userId)
        result <- auth.submitPasskeyAssertion(cookie, response.toJson, challenge.csrf)
        _ <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      yield assertTrue(result.response.status == Status.SeeOther)
        .label("a failed assertion re-renders the credential step via the normal submit redirect, not an OTP step")
    },
    test("passkey options are refused for a client whose flow does not offer a passkey") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        _ <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        result <- auth.getPasskeyOptions(cookie)
      yield assertTrue(result.status == Status.BadRequest)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

  /** Signs in with login+password, then enrolls a passkey through the Account Settings API
    * so a later passkey login has a registered credential to assert against.
    */
  private def enrollPasskey(s: Flows.Setup, auth: OAuthClient): Task[TestAuthenticator.Credential] =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
      idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("token response carries no id_token"))
      payload <- ZIO.attempt(
        String(java.util.Base64.getUrlDecoder.decode(idToken.split('.')(1)), java.nio.charset.StandardCharsets.UTF_8),
      )
      claims <- ZIO.fromEither(payload.fromJson[IdTokenClaims])
        .mapError(error => RuntimeException(s"id_token has no usable 'sid' claim [$error]: $payload"))
      caller = AccountCaller(s.userId, s.clientId, claims.sid)
      started <- auth.startPasskeyEnrollment(caller)
      ceremony <- ZIO.fromEither(started.body.fromJson[StartEnrollment])
        .mapError(error => RuntimeException(s"Unexpected enrollment start response [$error]: ${started.body}"))
      credential <- TestAuthenticator.create(ceremony.publicKeyOptions, passkeyOrigin)
      _ <- auth.finishPasskeyEnrollment(caller, ceremony.ticket, credential.responseJson, "E2E Passkey")
    yield credential

  private case class IdTokenClaims(sid: String) derives zio.json.JsonDecoder

  private case class StartEnrollment(publicKeyOptions: String, ticket: String) derives zio.json.JsonDecoder

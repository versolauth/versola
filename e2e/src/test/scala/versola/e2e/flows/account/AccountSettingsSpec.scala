package versola.e2e.flows.account

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

/** The self-service Security page served on auth's additional listener, exercised
  * the way edge calls it: Basic resource credentials plus the caller it authenticated.
  */
object AccountSettingsSpec extends E2ESpec:

  /** The origin the seeded challenge settings trust for passkeys (see `upsertChallengeSettings`). */
  private val passkeyOrigin = "http://localhost:3000"

  def spec = suite("Account Settings")(
    test("rejects a call without resource credentials") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.accountPage(caller, credentials = None)
      yield assertTrue(result.status == Status.Unauthorized)
    },
    test("rejects a call with a wrong resource secret") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.accountPage(caller, credentials = Some(("auth", "d3JvbmctYWNjb3VudC1yZXNvdXJjZS1zZWNyZXQ")))
      yield assertTrue(result.status == Status.Unauthorized)
    },
    test("renders the security page for the caller") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.accountPage(caller)
        step <- result.formStep
      yield assertTrue(result.status == Status.Ok) &&
        assertTrue(str(step, "type").contains("auth-settings"))
          .label("the page must render the account settings step")
    },
    test("lists sessions and flags the current one") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.accountPage(caller)
        sessions <- result.formItems("sessions")
      yield assertTrue(result.status == Status.Ok) &&
        assertTrue(sessions.exists(session => str(session, "id").contains(caller.sessionId) && flag(session, "current")))
          .label("the caller's own session must be listed and flagged current") &&
        assertTrue(sessions.filter(flag(_, "current")).size == 1)
          .label("exactly one session may be flagged current")
    },
    test("revokes another session of the same user") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        stale <- login(s, auth)
        caller <- login(s, auth)
        result <- auth.revokeAccountSession(caller, stale.sessionId)
        remaining <- auth.accountPage(caller).flatMap(_.formItems("sessions"))
      yield assertTrue(result.status == Status.NoContent) &&
        assertTrue(!remaining.exists(session => str(session, "id").contains(stale.sessionId)))
          .label("the revoked session must be gone from the list")
    },
    test("reports no content when revoking an unknown session") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.revokeAccountSession(caller, UUID.randomUUID().toString)
      yield assertTrue(result.status == Status.NoContent)
    },
    test("enrolls, renames and deletes a passkey through the full ceremony") {
      val name = "E2E Key"
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        started <- auth.startPasskeyEnrollment(caller)
        ceremony <- enrollment(started)
        credential <- TestAuthenticator.create(ceremony.publicKeyOptions, passkeyOrigin)
        finished <- auth.finishPasskeyEnrollment(caller, ceremony.ticket, credential.responseJson, name)
        enrolled <- finished.json
        listed <- auth.accountPage(caller).flatMap(_.formItems("passkeys"))
        renamed <- auth.renameAccountPasskey(caller, credential.id, Some("Renamed Key"))
        afterRename <- auth.accountPage(caller).flatMap(_.formItems("passkeys"))
        deleted <- auth.deleteAccountPasskey(caller, credential.id)
        afterDelete <- auth.accountPage(caller).flatMap(_.formItems("passkeys"))
      yield assertTrue(started.status == Status.Ok) &&
        assertTrue(finished.status == Status.Ok).label(s"enrollment must succeed, got: ${finished.body}") &&
        assertTrue(str(enrolled, "id").contains(credential.id))
          .label("the enrolled passkey must carry the credential id the authenticator minted") &&
        assertTrue(str(enrolled, "name").contains(name)) &&
        assertTrue(listed.exists(passkey => str(passkey, "id").contains(credential.id))) &&
        assertTrue(renamed.status == Status.NoContent) &&
        assertTrue(afterRename.exists(passkey =>
          str(passkey, "id").contains(credential.id) && str(passkey, "name").contains("Renamed Key"),
        )).label("the rename must be visible in the list") &&
        assertTrue(deleted.status == Status.NoContent) &&
        assertTrue(!afterDelete.exists(passkey => str(passkey, "id").contains(credential.id)))
          .label("the deleted passkey must be gone from the list")
    },
    test("rejects enrollment for a client that has no passkey settings") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.startPasskeyEnrollment(caller.copy(clientId = s"unknown-client-${UUID.randomUUID()}"))
      yield assertTrue(result.status == Status.BadRequest) &&
        assertTrue(result.body.contains("passkeys are not enabled"))
    },
    test("rejects a tampered enrollment ticket") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        ceremony <- auth.startPasskeyEnrollment(caller).flatMap(enrollment)
        credential <- TestAuthenticator.create(ceremony.publicKeyOptions, passkeyOrigin)
        result <- auth.finishPasskeyEnrollment(caller, s"${ceremony.ticket}x", credential.responseJson, "Tampered")
      yield assertTrue(result.status == Status.BadRequest)
    },
    test("rejects an enrollment ticket minted for another user") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        ceremony <- auth.startPasskeyEnrollment(caller).flatMap(enrollment)
        credential <- TestAuthenticator.create(ceremony.publicKeyOptions, passkeyOrigin)
        result <- auth.finishPasskeyEnrollment(
          caller.copy(userId = UUID.randomUUID()),
          ceremony.ticket,
          credential.responseJson,
          "Foreign",
        )
      yield assertTrue(result.status == Status.Unauthorized)
    },
    test("rejects an empty passkey name") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        ceremony <- auth.startPasskeyEnrollment(caller).flatMap(enrollment)
        credential <- TestAuthenticator.create(ceremony.publicKeyOptions, passkeyOrigin)
        result <- auth.finishPasskeyEnrollment(caller, ceremony.ticket, credential.responseJson, "   ")
      yield assertTrue(result.status == Status.BadRequest)
    },
    test("rejects an authenticator response the ceremony cannot verify") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        ceremony <- auth.startPasskeyEnrollment(caller).flatMap(enrollment)
        result <- auth.finishPasskeyEnrollment(caller, ceremony.ticket, Json.Obj(), "Malformed")
      yield assertTrue(result.status == Status.BadRequest)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)

  /** Signs in through the real authorization flow, then rebuilds the caller identity edge
    * would forward: the authenticated user, the client, and the session `sid` of the id token.
    */
  private def login(setup: Flows.Setup, auth: OAuthClient): Task[AccountCaller] =
    for
      authorize <- auth.authorize(clientId = Some(setup.clientId), redirectUri = Some(setup.redirectUri))
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, setup.login.get, setup.password, challenge.csrf)
        .assertRedirect(auth, cookie)
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(setup.clientId),
        clientSecret = Some(setup.clientSecret),
        redirectUri = Some(setup.redirectUri),
      ).success
      sessionId <- sessionIdOf(token)
    yield AccountCaller(setup.userId, setup.clientId, sessionId)

  private def sessionIdOf(token: TokenResult.Success): Task[String] =
    for
      idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("token response carries no id_token"))
      payload <- ZIO.attempt(String(Base64.getUrlDecoder.decode(idToken.split('.')(1)), StandardCharsets.UTF_8))
      claims <- ZIO.fromEither(payload.fromJson[IdTokenClaims])
        .mapError(error => RuntimeException(s"id_token has no usable 'sid' claim [$error]: $payload"))
    yield claims.sid

  private def enrollment(result: AccountResult): Task[StartEnrollment] =
    ZIO.fromEither(result.body.fromJson[StartEnrollment])
      .mapError(error => RuntimeException(s"Unexpected enrollment start response [$error]: ${result.body}"))

  private def str(obj: Json.Obj, name: String): Option[String] =
    obj.fields.collectFirst { case (field, Json.Str(value)) if field == name => value }

  private def flag(obj: Json.Obj, name: String): Boolean =
    obj.fields.collectFirst { case (field, Json.Bool(value)) if field == name => value }.getOrElse(false)

  private case class IdTokenClaims(sid: String) derives JsonDecoder

  private case class StartEnrollment(publicKeyOptions: String, ticket: String) derives JsonDecoder

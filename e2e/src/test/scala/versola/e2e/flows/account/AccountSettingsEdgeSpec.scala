package versola.e2e.flows.account

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The Security page the way the browser actually reaches it: through the edge proxy.
  * Edge authenticates the bearer token, authorizes it against the synced role/permission
  * mappings, evaluates the endpoint's access rule and injects the caller context — none
  * of which the direct-to-auth [[AccountSettingsSpec]] exercises.
  */
object AccountSettingsEdgeSpec extends E2ESpec:

  /** The origin the seeded challenge settings trust for passkeys (see `upsertChallengeSettings`). */
  private val passkeyOrigin = "http://localhost:3000"

  /** The seeded self-service role: the only one carrying `auth-settings:manage`, which is what
    * edge's deny-by-default permission check grants the account endpoints against. */
  private val accountRole = "user"

  def spec = suite("Account Settings through edge")(
    test("rejects a call without an access token") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        result <- auth.edgeAccountPage(None)
      yield assertTrue(result.status == Status.Unauthorized)
    },
    test("rejects a caller whose roles carry no account permission") {
      for
        (s, auth) <- setup(Flows.Id.PasskeyLogin)
        caller <- login(s, auth)
        result <- auth.edgeAccountPage(Some(caller.accessToken))
      yield assertTrue(result.status == Status.Forbidden)
    },
    test("renders the security page for the authenticated caller") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.edgeAccountPage(Some(caller.accessToken))
        step <- result.formStep
      yield assertTrue(result.status == Status.Ok) &&
        assertTrue(str(step, "type").contains("auth-settings"))
          .label("the page must render the account settings step")
    },
    test("lists sessions and flags the one the token was issued for") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.edgeAccountPage(Some(caller.accessToken))
        sessions <- result.formItems("sessions")
      yield assertTrue(result.status == Status.Ok) &&
        assertTrue(sessions.exists(session => str(session, "id").contains(caller.sessionId) && flag(session, "current")))
          .label("the caller's own session must be listed and flagged current") &&
        assertTrue(sessions.filter(flag(_, "current")).size == 1)
          .label("exactly one session may be flagged current")
    },
    test("refuses to revoke the session the caller is using") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        result <- auth.edgeRevokeAccountSession(Some(caller.accessToken), caller.sessionId)
        remaining <- auth.edgeAccountPage(Some(caller.accessToken)).flatMap(_.formItems("sessions"))
      yield assertTrue(result.status == Status.Forbidden)
          .label("the endpoint's access rule must reject revoking `token.sid`") &&
        assertTrue(remaining.exists(session => str(session, "id").contains(caller.sessionId)))
          .label("the caller's session must survive the refused revocation")
    },
    test("revokes another session of the same user") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        stale <- login(s, auth)
        caller <- login(s, auth)
        result <- auth.edgeRevokeAccountSession(Some(caller.accessToken), stale.sessionId)
        remaining <- auth.edgeAccountPage(Some(caller.accessToken)).flatMap(_.formItems("sessions"))
      yield assertTrue(result.status == Status.NoContent) &&
        assertTrue(!remaining.exists(session => str(session, "id").contains(stale.sessionId)))
          .label("the revoked session must be gone from the page")
    },
    test("enrolls, renames and deletes a passkey through the full ceremony") {
      val name = "E2E Edge Key"
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        caller <- login(s, auth)
        token = Some(caller.accessToken)
        started <- auth.edgeStartPasskeyEnrollment(token)
        ceremony <- enrollment(started)
        credential <- TestAuthenticator.create(ceremony.publicKeyOptions, passkeyOrigin)
        finished <- auth.edgeFinishPasskeyEnrollment(token, ceremony.ticket, credential.responseJson, name)
        listed <- auth.edgeAccountPage(token).flatMap(_.formItems("passkeys"))
        renamed <- auth.edgeRenameAccountPasskey(token, credential.id, Some("Renamed Edge Key"))
        afterRename <- auth.edgeAccountPage(token).flatMap(_.formItems("passkeys"))
        deleted <- auth.edgeDeleteAccountPasskey(token, credential.id)
        afterDelete <- auth.edgeAccountPage(token).flatMap(_.formItems("passkeys"))
      yield assertTrue(started.status == Status.Ok) &&
        assertTrue(finished.status == Status.Ok).label(s"enrollment must succeed, got: ${finished.body}") &&
        assertTrue(listed.exists(passkey => str(passkey, "id").contains(credential.id))) &&
        assertTrue(renamed.status == Status.NoContent) &&
        assertTrue(afterRename.exists(passkey =>
          str(passkey, "id").contains(credential.id) && str(passkey, "name").contains("Renamed Edge Key"),
        )).label("the rename must be visible on the page") &&
        assertTrue(deleted.status == Status.NoContent) &&
        assertTrue(!afterDelete.exists(passkey => str(passkey, "id").contains(credential.id)))
          .label("the deleted passkey must be gone from the page")
    },
  ) @@ TestAspect.beforeAll(grantAccountRole) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)

  /** Edge authorizes against the roles baked into the access token, and the shared e2e user
    * is created without any — so the self-service role is granted (and flushed to auth)
    * once before the suite rather than per test.
    */
  private lazy val grantAccountRole: ZIO[Flows.Setups, Nothing, Unit] =
    (for
      (s, auth) <- setup(Flows.Id.LoginPassword)
      _ <- auth.assignUserRoles(s.userId, Set(accountRole))
      _ <- auth.flushUserOutbox()
    yield ()).orDie

  /** Signs in through the real authorization flow and keeps what the browser would keep:
    * the access token edge authenticates, and the `sid` it will inject as the caller's session.
    */
  private def login(setup: Flows.Setup, auth: OAuthClient): Task[Caller] =
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
    yield Caller(token.accessToken, sessionId)

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

  private case class Caller(accessToken: String, sessionId: String)

  private case class IdTokenClaims(sid: String) derives JsonDecoder

  private case class StartEnrollment(publicKeyOptions: String, ticket: String) derives JsonDecoder

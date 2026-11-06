package versola.e2e.flows.revocation

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.test.*

import java.util.UUID

/** `auth` refuses what it revoked, by itself: the client here registered no
  * `back_channel_logout_uri`, so nothing is pushed anywhere and nobody but `auth`'s own record
  * of the revocation can reject the token (#729). Revocations spread to every `auth` replica by
  * notification, so each case polls the endpoint, within a bound, for the refusal to appear
  * rather than asserting the instant after the call.
  */
object AuthRevocationRecordFlowSpec extends E2ESpec:

  /** How long a revocation may take to reach the replica that answers: a notification, normally
    * milliseconds. Generous, because what is awaited is the refusal, not the speed. */
  private val Propagation = 15.seconds

  private case class OAuthError(error: String) derives JsonDecoder

  private def login(s: Flows.Setup, auth: OAuthClient): Task[(TokenResult.Success, String)] =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
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
    yield (token, code)

  /** The code of a login, kept so that it can be presented a second time. */
  private def loginKeepingCode(s: Flows.Setup, auth: OAuthClient) =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
    yield (code, authorize.verifier)

  private def exchange(s: Flows.Setup, auth: OAuthClient, code: String, verifier: String) =
    auth.token(
      code,
      verifier,
      clientId = Some(s.clientId),
      clientSecret = Some(s.clientSecret),
      redirectUri = Some(s.redirectUri),
    )

  private def userinfoStatus(auth: OAuthClient, accessToken: String): Task[Status] =
    auth.userinfo(accessToken).map(_.response.status)

  /** Polls `/userinfo` until it refuses the token, or fails once `Propagation` has passed. */
  private def awaitRefused(auth: OAuthClient, accessToken: String): Task[Status] =
    userinfoStatus(auth, accessToken)
      .repeat(Schedule.spaced(100.millis) && Schedule.recurUntil[Status](_ == Status.Unauthorized))
      .map(_._2)
      .timeoutFail(RuntimeException(s"/userinfo still honoured the token after $Propagation"))(Propagation)

  /** Polls `/introspect` until it reports the token inactive, or fails once `Propagation` has passed. */
  private def awaitInactive(auth: OAuthClient, accessToken: String, clientId: String, secret: String) =
    auth.introspect(accessToken, Some(clientId), Some(secret)).success
      .repeat(Schedule.spaced(100.millis) && Schedule.recurUntil[IntrospectResult.Success](!_.active))
      .map(_._2)
      .timeoutFail(RuntimeException(s"/introspect still reported the token active after $Propagation"))(Propagation)

  def spec = suite("Access token revocation recorded by auth")(
    test("a revoked access token is refused at /userinfo although its client has no back channel") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        (revoked, _) <- login(s, auth)
        (surviving, _) <- login(s, auth)
        before <- userinfoStatus(auth, revoked.accessToken)
        revokeResponse <- auth.revoke(revoked.accessToken, s.clientId, s.clientSecret)
        after <- awaitRefused(auth, revoked.accessToken)
        other <- userinfoStatus(auth, surviving.accessToken)
      yield assertTrue(before == Status.Ok).label("the token must be honoured before it is revoked") &&
        assertTrue(revokeResponse.status == Status.Ok).label("/revoke answers 200") &&
        assertTrue(after == Status.Unauthorized).label("the revoked token must be refused") &&
        assertTrue(other == Status.Ok).label("revoking one token leaves the same client's other token working")
    },
    test("a revoked access token is reported inactive at /introspect") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        // A client of its own, holding the resource its token is for: access-token introspection is
        // scoped to the resources the caller can reach, which a login client's token is not.
        clientId = s"rev-client-${UUID.randomUUID().toString.replace("-", "").take(8)}"
        registered <- auth.registerClient(
          clientId,
          "Revocation record test client",
          Set("http://localhost:3000"),
          allowedScopes = Set("openid", "email"),
        ).success
        resource = s"https://$clientId.example.test"
        _ <- auth.registerResource(s"res-$clientId", resource, audience = Set(clientId))
        _ <- auth.syncConfiguration()
        token <- auth.clientCredentials(clientId, registered.secret, scope = Some("email"), resources = Some(List(resource))).success
        untouched <- auth.clientCredentials(clientId, registered.secret, scope = Some("email"), resources = Some(List(resource))).success
        before <- auth.introspect(token.accessToken, Some(clientId), Some(registered.secret)).success
        _ <- auth.revoke(token.accessToken, clientId, registered.secret)
        after <- awaitInactive(auth, token.accessToken, clientId, registered.secret)
        other <- auth.introspect(untouched.accessToken, Some(clientId), Some(registered.secret)).success
      yield assertTrue(before.active).label("the token must introspect as active before it is revoked") &&
        assertTrue(!after.active).label("the revoked token must introspect as inactive") &&
        assertTrue(other.active).label("another token of the same client must stay active")
    },
    test("replaying an authorization code ends the tokens its first exchange issued") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        (code, verifier) <- loginKeepingCode(s, auth)
        first <- exchange(s, auth, code, verifier).success
        before <- userinfoStatus(auth, first.accessToken)
        // RFC 6749 §4.1.2: a code used twice is an attack on the first use, and the server SHOULD revoke the
        // tokens it issued; the conformance suite's `attempt-reuse-authorization-code` checks that.
        replay <- exchange(s, auth, code, verifier)
        replayError <- replay match
          case TokenResult.Failure(response, body) =>
            ZIO.succeed(response.status -> body.fromJson[OAuthError].toOption.map(_.error))
          case other => ZIO.fail(RuntimeException(s"Expected the replay to be rejected, got $other"))
        after <- awaitRefused(auth, first.accessToken)
      yield assertTrue(before == Status.Ok).label("the first exchange's token works until the replay") &&
        assertTrue(replayError == (Status.BadRequest -> Some("invalid_grant"))).label("the replay is refused with invalid_grant") &&
        assertTrue(after == Status.Unauthorized).label("the token the first exchange issued must be refused after the replay")
    },
    test("a login after a replay elsewhere is not affected") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        (code, verifier) <- loginKeepingCode(s, auth)
        _ <- exchange(s, auth, code, verifier).success
        _ <- exchange(s, auth, code, verifier)
        // A new grant has a new refresh-token family: what the replay revoked is the first one's.
        (fresh, _) <- login(s, auth)
        status <- userinfoStatus(auth, fresh.accessToken)
      yield assertTrue(status == Status.Ok)
    },
    test("logging out ends the session's access tokens at /userinfo") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        (token, _) <- login(s, auth)
        idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("expected an id_token for the openid scope"))
        before <- userinfoStatus(auth, token.accessToken)
        _ <- auth.logoutWithIdTokenHint(idToken)
        after <- awaitRefused(auth, token.accessToken)
      yield assertTrue(before == Status.Ok) && assertTrue(after == Status.Unauthorized)
    },
  )

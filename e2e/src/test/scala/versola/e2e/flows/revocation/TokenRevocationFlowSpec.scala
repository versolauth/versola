package versola.e2e.flows.revocation

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.test.*

/** Ending a token's life at auth has to reach the edge that enforces it, which is the whole
  * point of the exercise: a JWT the edge validates on its own signature would otherwise stay
  * good until it expired.
  *
  * Both routes are covered, because they are not the same thing: `/revoke` ends one token and
  * a logout ends every token of a session.
  */
object TokenRevocationFlowSpec extends E2ESpec:

  private case class Login(accessToken: String, idToken: String)

  /** Logs in and exchanges the code. */
  private def login(s: Flows.Setup, auth: OAuthClient): Task[Login] =
    for
      authorize <- auth.authorize(
        clientId = Some(s.clientId),
        redirectUri = Some(s.redirectUri),
      ).assertChallengeRedirect
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
      idToken <- ZIO.fromOption(token.idToken)
        .orElseFail(RuntimeException("expected an id_token for the openid scope"))
    yield Login(token.accessToken, idToken)

  private def edgeStatus(auth: OAuthClient, accessToken: String): Task[Status] =
    auth.edgePermissions(accessToken).map(_.status)

  /** The edge only accepts security events for clients it knows about, and it learns about a
    * freshly registered one on its next configuration refresh. Both of these are idempotent,
    * so the request is simply repeated until the edge starts rejecting the token.
    */
  private def repeatUntilRejected(request: Task[Any], auth: OAuthClient, accessToken: String): Task[Status] =
    (request *> edgeStatus(auth, accessToken))
      .repeat(Schedule.spaced(1.second) && Schedule.recurUntil[Status](_ == Status.Unauthorized))
      .map(_._2)

  def spec = suite("Token revocation")(
    test("a revoked access token stops being accepted by the edge") {
      for
        (s, auth) <- setup(Flows.Id.BackChannelLogout)
        session <- login(s, auth)
        before <- edgeStatus(auth, session.accessToken)
        after <- repeatUntilRejected(
          auth.revoke(session.accessToken, s.clientId, s.clientSecret),
          auth,
          session.accessToken,
        )
      yield assertTrue(before == Status.Ok)
        .label("the edge must accept the token before it is revoked") &&
        assertTrue(after == Status.Unauthorized)
          .label("the edge must reject the token once auth has revoked it")
    },
    test("revoking one token leaves the client's other tokens working") {
      for
        (s, auth) <- setup(Flows.Id.BackChannelLogout)
        revokedSession <- login(s, auth)
        survivingSession <- login(s, auth)
        _ <- repeatUntilRejected(
          auth.revoke(revokedSession.accessToken, s.clientId, s.clientSecret),
          auth,
          revokedSession.accessToken,
        )
        surviving <- edgeStatus(auth, survivingSession.accessToken)
      yield
        // A revocation names one token. Rejecting every token of the client, or of the user,
        // would end sessions nobody asked to end.
        assertTrue(surviving == Status.Ok)
          .label("a token that was not revoked must still be accepted")
    },
    test("logging out stops the session's access tokens being accepted by the edge") {
      for
        (s, auth) <- setup(Flows.Id.BackChannelLogout)
        session <- login(s, auth)
        before <- edgeStatus(auth, session.accessToken)
        // The bearer token is still signed and unexpired after the logout, and the edge has
        // no session cookie to drop for it: only the revocation keyed by `sid` stops it.
        after <- repeatUntilRejected(
          auth.logoutWithIdTokenHint(session.idToken),
          auth,
          session.accessToken,
        )
      yield assertTrue(before == Status.Ok)
        .label("the edge must accept the token before the logout") &&
        assertTrue(after == Status.Unauthorized)
          .label("the edge must reject the session's token once the session has been logged out")
    },
    test("an administrator ending a user's access stops every session that user has") {
      for
        (s, auth) <- setup(Flows.Id.BackChannelLogout)
        first <- login(s, auth)
        second <- login(s, auth)
        // One event per client covers both sessions, so it is enough to poll on one of them.
        _ <- repeatUntilRejected(auth.invalidateUserSessions(s.userId), auth, first.accessToken)
        other <- edgeStatus(auth, second.accessToken)
      yield assertTrue(other == Status.Unauthorized)
        .label("every session of the user must be rejected, not just one")
    },
    test("a user can log in again straight after an administrator ends their access") {
      for
        (s, auth) <- setup(Flows.Id.BackChannelLogout)
        old <- login(s, auth)
        _ <- repeatUntilRejected(auth.invalidateUserSessions(s.userId), auth, old.accessToken)
        // Past the second the revocation was recorded in: `iat` is whole seconds, and a
        // token minted in that same second is deliberately treated as one it covers.
        _ <- ZIO.sleep(1100.millis)
        // The revocation is still live at this point — it outlives the tokens it was aimed
        // at — so a token minted after it must be told apart from the ones it revoked.
        fresh <- login(s, auth)
        status <- edgeStatus(auth, fresh.accessToken)
      yield assertTrue(status == Status.Ok)
        .label("a session started after the revocation must be accepted")
    },
    test("logging out of one session leaves another session of the same user working") {
      for
        (s, auth) <- setup(Flows.Id.BackChannelLogout)
        endedSession <- login(s, auth)
        survivingSession <- login(s, auth)
        _ <- repeatUntilRejected(
          auth.logoutWithIdTokenHint(endedSession.idToken),
          auth,
          endedSession.accessToken,
        )
        surviving <- edgeStatus(auth, survivingSession.accessToken)
      yield assertTrue(surviving == Status.Ok)
        .label("a session that was not logged out must still be accepted")
    },
    // The revocations under test are recorded against wall-clock expiry and polled for over
    // real seconds, so the tests need the live clock rather than the test one. That polling,
    // once per test and run sequentially, is why this suite alone takes 40+ real seconds —
    // not a hang.
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(90.seconds)

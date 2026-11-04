package versola.e2e.flows.audit

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** What auth writes when something is refused: a wrong password at sign-in, which is about a user and read back per user, and the refused
  * token requests (a refresh that asks for more than was granted, a code exchange with the wrong PKCE verifier), which are counted in
  * memory and written once a minute without a user, so they are read from the counter of events that auth publishes instead. */
object AuditLogRefusalFlowSpec extends E2ESpec:

  private case class Row(kind: String, outcome: String, reason: Option[String], details: Json.Obj)

  private def rows(auth: OAuthClient, userId: String, from: Instant): Task[List[Row]] =
    for
      response <- auth.auditGet(s"/audit/users/$userId/events?from=$from&limit=500")
      body <- response.body.asString
      json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield json.get("events").flatMap(_.asArray).map(_.toList.flatMap(_.asObject).flatMap: obj =>
      for
        kind <- obj.get("kind").flatMap(_.asString)
        outcome <- obj.get("outcome").flatMap(_.asString)
      yield Row(kind, outcome, obj.get("reason").flatMap(_.asString), obj.get("details").flatMap(_.asObject).getOrElse(Json.Obj()))).getOrElse(Nil)

  private def await(auth: OAuthClient, userId: String, from: Instant)(done: List[Row] => Boolean): Task[List[Row]] =
    def attempt(left: Int): Task[List[Row]] =
      rows(auth, userId, from).flatMap(found => if done(found) || left == 0 then ZIO.succeed(found) else ZIO.sleep(250.millis) *> attempt(left - 1))
    attempt(80)

  /** `audit_events_total{...,kind="token.refused",...}` summed, from auth's diagnostics endpoint. */
  private def refusedCounter(auth: OAuthClient): Task[Double] =
    for
      response <- auth.probe(zio.http.Method.GET, s"${auth.authDiagnosticsBaseUrl}/metrics")
      body <- response.body.asString
    yield body.linesIterator
      .filter(line => line.startsWith("audit_events_total") && line.contains("kind=\"token.refused\""))
      .flatMap(_.split(' ').lastOption.flatMap(_.toDoubleOption))
      .sum

  /** The counter once it is above `before`, polled for up to `left` times a quarter second. */
  private def counterAbove(auth: OAuthClient, before: Double, left: Int): Task[Double] =
    refusedCounter(auth).flatMap(now =>
      if now > before || left == 0 then ZIO.succeed(now) else ZIO.sleep(250.millis) *> counterAbove(auth, before, left - 1),
    )

  def spec = suite("Audit log of refusals")(
    test("a wrong password at sign-in is recorded as login.step failure invalid_password, and carries neither password") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        started = Instant.now.minusMillis(200)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitLoginPassword(cookie, s.login.get, "Not-the-password-1!", challenge.csrf)
        stored <- await(auth, s.userId.toString, started)(_.exists(_.kind == "login.step"))
        steps = stored.filter(_.kind == "login.step")
      yield assertTrue(
        steps.map(r => (r.outcome, r.reason)) == List(("failure", Some("invalid_password"))),
        steps.head.details.get("step").flatMap(_.asString).contains("password"),
        !stored.exists(r => r.details.toJson.contains("Not-the-password-1!") || r.details.toJson.contains(s.password)),
        // nothing was issued to someone who did not sign in
        !stored.exists(_.kind == "login.completed"),
      )
    },
    test(
      "a refresh that asks for more scope than was granted and a code exchange with the wrong PKCE verifier are counted as refused tokens, a row each a minute",
    ) {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        before <- refusedCounter(auth)
        authorize <-
          auth.authorize(scope = "openid offline_access", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, s.email.get, credential.csrf)
        otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, "123456", otp.csrf).flatMap(_.assertRedirect)
        tokens <- auth.token(code, authorize.verifier, Some(s.clientId), Some(s.clientSecret), Some(s.redirectUri)).success
        refresh <- ZIO.fromOption(tokens.refreshToken).orElseFail(RuntimeException("expected a refresh token"))
        refusedRefresh <- auth.refresh(refresh, Some(s.clientId), Some(s.clientSecret), scope = Some("openid offline_access admin:everything"))
        // a sign-in of another client, whose code is exchanged with a verifier that is not the one it was made from
        (s2, auth2) <- setup(Flows.Id.LoginPassword)
        second <- auth2.authorize(clientId = Some(s2.clientId), redirectUri = Some(s2.redirectUri)).assertChallengeRedirect
        secondCookie = second.conversationCookie.get
        challenge <- auth2.getChallenge(secondCookie).assertStep(ConversationStep.Credential)
        secondCode <- auth2.submitLoginPassword(secondCookie, s2.login.get, s2.password, challenge.csrf).assertRedirect(auth2, secondCookie)
        refusedExchange <- auth2.token(
          secondCode,
          "a-verifier-that-is-not-the-one-the-challenge-was-made-from-123456",
          Some(s2.clientId),
          Some(s2.clientSecret),
          Some(s2.redirectUri),
        )
        // refusals are counted in memory and written once a minute, one row for each client, address, reason and grant: the counter moves then
        after <- counterAbove(auth, before + 1, 300)
      yield assertTrue(
        refusedRefresh.isInstanceOf[TokenResult.Failure],
        refusedExchange.isInstanceOf[TokenResult.Failure],
        after >= before + 2,
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(240.seconds)

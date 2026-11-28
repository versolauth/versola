package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** The tenant's limit on password attempts holds against requests that arrive together, over HTTP against the staged stack.
  *
  * A limit that is read before a password is checked and written after it lets every request of a burst through the read, so
  * each of them checks a guess; auth charges the attempt before the password is looked at instead, and gives it back for a
  * right password. The attempts that were checked are counted from the audit log, which records one `login.step` for each
  * password that was looked at and one for each that was refused without being looked at.
  *
  * The limits are the tenant's, so each case sets its own and the spec puts back what the bootstrap had before it ends
  * (the next spec's bootstrap would do it too). The cases run one after the other for the same reason.
  */
object PasswordAttemptLimitSpec extends E2ESpec:

  // 3 attempts in 15 minutes is the limit that matters; the longer window only has to be wider for the ban to be a separate rule.
  private val limits = """[{"maxAttempts":3,"windowSeconds":900},{"maxAttempts":6,"windowSeconds":3600}]"""
  private val burst = 20

  private val bootstrapVocabulary =
    Map(Acr.OtpLevel -> List("otp"), Acr.PasswordLevel -> List("password"), Acr.PasskeyLevel -> List("passkey"))

  private def setLimits(auth: OAuthClient, passwordSubmitLimits: String, banDurationSeconds: Int): Task[Unit] =
    auth.upsertChallengeSettings(
      acrVocabulary = bootstrapVocabulary,
      mtlsCertificateHeader = Some(OAuthClient.mtlsCertificateHeader),
      mtlsCertificateEncoding = Some("urlEncodedPem"),
      passwordSubmitLimits = passwordSubmitLimits,
      banDurationSeconds = banDurationSeconds,
    ) *> auth.syncConfiguration()

  /** A client and a user of their own: a burst bans its user, and the shared one belongs to every other spec. */
  private def withLimitedUser[A](use: (Flows.Setup, OAuthClient) => Task[A]): ZIO[Flows.Setups, Throwable, A] =
    ZIO.serviceWithZIO[Flows.Setups]: setups =>
      val auth = setups.client
      ZIO.acquireReleaseWith(setLimits(auth, limits, banDurationSeconds = 1800))(_ => setLimits(auth, "[]", 0).orDie): _ =>
        for
          s <- Flows.setupLoginPassword().provideEnvironment(ZEnvironment(auth))
          _ <- auth.syncConfiguration()
          result <- use(s, auth)
        yield result

  /** A conversation at the credential card, as the browser has it after /authorize. */
  private def startLogin(s: Flows.Setup, auth: OAuthClient): Task[(String, String)] =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
    yield (cookie, challenge.csrf)

  /** What the audit log holds of the user's password steps since `from`: (looked at and wrong, refused without being looked at). The rows
    * reach the table in the writer's batches, up to a second after the request, so this waits (bounded) until every request is accounted for. */
  private def passwordSteps(auth: OAuthClient, userId: String, from: Instant, expected: Int): Task[(Int, Int)] =
    def rows: Task[List[(String, String, Option[String])]] =
      for
        response <- auth.auditGet(s"/users/audit-events?id=$userId&from=$from&limit=500")
        body <- response.body.asString
        _ <- ZIO.fail(RuntimeException(s"events answered ${response.status}: $body")).unless(response.status == Status.Ok)
        json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
      yield json.get("events").flatMap(_.asArray).map(_.toList).getOrElse(Nil).flatMap: event =>
        for
          obj <- event.asObject
          kind <- obj.get("kind").flatMap(_.asString)
          outcome <- obj.get("outcome").flatMap(_.asString)
          step = obj.get("details").flatMap(_.asObject).flatMap(_.get("step")).flatMap(_.asString)
          if kind == "login.step" && step.contains("password")
        yield (kind, outcome, obj.get("reason").flatMap(_.asString))

    def attempt(left: Int): Task[(Int, Int)] =
      rows.flatMap: found =>
        val checked = found.count((_, outcome, reason) => outcome == "failure" && reason.contains("invalid_password"))
        val refused = found.count((_, outcome, _) => outcome == "denied")
        if checked + refused >= expected || left == 0 then ZIO.succeed((checked, refused))
        else ZIO.sleep(250.millis) *> attempt(left - 1)
    attempt(80)

  def spec = suite("Password attempt limits")(
    test("a burst of wrong passwords is checked no more often than the limit allows, however the requests interleave") {
      withLimitedUser: (s, auth) =>
        for
          started = Instant.now.minusMillis(500)
          (cookie, csrf) <- startLogin(s, auth)
          _ <- ZIO.foreachParDiscard(1 to burst)(i => auth.submitLoginPassword(cookie, s.login.get, s"Wrong-$i-9!x", csrf))
          (checked, refused) <- passwordSteps(auth, s.userId.toString, started, expected = burst)
        yield assertTrue(checked == 3, refused == burst - 3)
    },
    test("a right password is refused once the limit is reached") {
      withLimitedUser: (s, auth) =>
        for
          started = Instant.now.minusMillis(500)
          (cookie, csrf) <- startLogin(s, auth)
          _ <- ZIO.foreachParDiscard(1 to burst)(i => auth.submitLoginPassword(cookie, s.login.get, s"Wrong-$i-9!x", csrf))
          _ <- passwordSteps(auth, s.userId.toString, started, expected = burst)
          (cookie2, csrf2) <- startLogin(s, auth)
          // refused although the password is right: no code, and the credential card is shown again with the failure flag, not an error page
          refused <- auth.submitLoginPassword(cookie2, s.login.get, s.password, csrf2)
          noCode <- ZIO.succeed(refused).assertRedirect(auth, cookie2).exit
          page <- auth.getChallenge(cookie2)
        yield assertTrue(
          noCode.isFailure,
          page.step.contains(ConversationStep.Credential),
          page.html.contains("\"login_failed\""),
        )
    },
    test("a burst of wrong current passwords on the account password change is checked no more often than the limit allows") {
      withLimitedUser: (s, auth) =>
        val caller = AccountCaller(s.userId, s.clientId, sessionId = "unused")
        for
          results <- ZIO.foreachPar(1 to burst)(i => auth.changeAccountPassword(caller, s"Wrong-$i-9!x", "Brand-New-1!aa"))
          // the same budget as the sign-in form's password step: once it is used up, the right password is refused here too
          refusedRight <- auth.changeAccountPassword(caller, s.password, "Brand-New-1!aa")
        yield assertTrue(
          results.count(_.status == Status.BadRequest) == 3,
          results.count(_.status == Status.TooManyRequests) == burst - 3,
          refusedRight.status == Status.TooManyRequests,
        )
    },
    test("a right password spends none of the budget: more successful sign-ins than the limit allows all succeed") {
      withLimitedUser: (s, auth) =>
        for
          codes <- ZIO.foreach(1 to 5): _ =>
            for
              (cookie, csrf) <- startLogin(s, auth)
              code <- auth.submitLoginPassword(cookie, s.login.get, s.password, csrf).assertRedirect(auth, cookie)
            yield code
        yield assertTrue(codes.forall(_.nonEmpty), codes.size == 5)
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(180.seconds)

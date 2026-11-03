package versola.e2e.flows.antifraud

import versola.e2e.support.AntifraudFixture.{ListEntry, PasskeyUser}
import versola.e2e.support.AntifraudInstance.Decision
import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Client, Status}
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

/** The passkey in place of the one-time code, over HTTP: what the code page offers, what choosing it does, and what the
  * antifraud decision leaves of it.
  *
  * The unit specs prove the rules against stubs; this proves them through the wiring: a passkey enrolled through the
  * Account Settings API and found by an `auth` instance of the antifraud fixture (the same database, a decision that
  * enforces), a list entry made through central's admin API on the number, the forms' own posts, the passkey ceremony signed
  * by a software authenticator, and the event the instance wrote.
  *
  * A code is "123456" outside production, so the page of a faked or refused send, which nobody was sent anything for, is
  * told from a real one by the decision the request logged (`outcome`: `sent`, `faked`, `blocked`).
  */
object CodeAlternativeSpec extends ZIOSpec[AntifraudFixture & E2EConfig & Client & OAuthClient & CentralApi]:

  override val bootstrap: ZLayer[Any, Any, AntifraudFixture & E2EConfig & Client & OAuthClient & CentralApi] =
    val clients = (E2EConfig.live ++ Client.default) >+> OAuthClient.live
    clients >+> CentralApi.live >+> AntifraudFixture.layer

  private val Path = "/challenge/phone"
  private val PasskeyOffered = "\"alternatives\":[\"passkey\"]"
  private val NothingOffered = "\"alternatives\":[]"

  /** An address of its own, so that the line of a request can be told from the others and no count is shared. */
  private def randomIp(): String =
    val bits = UUID.randomUUID().getLeastSignificantBits
    s"10.${(bits >>> 16) & 0xff}.${(bits >>> 8) & 0xff}.${(bits & 0xff).max(3)}"

  private def awaited[A](what: String, timeout: Duration = 20.seconds, every: Duration = 200.millis)(probe: Task[Option[A]]): Task[A] =
    def loop: Task[A] =
      probe.flatMap:
        case Some(found) => ZIO.succeed(found)
        case None => ZIO.sleep(every) *> loop
    loop.timeoutFail(RuntimeException(s"timed out waiting for $what"))(timeout)

  /** The conversation of a person who has entered their number: the cookie, what `/authorize` said (for the token) and the page. */
  private case class OnPage(authorize: AuthorizeResult, cookie: String, page: ChallengeResult)

  /** Opens a conversation on `instance`, enters the setup's number from `ip`, and reads the page it comes to. */
  private def enterNumber(instance: AntifraudInstance, s: Flows.Setup, ip: String): Task[OnPage] =
    val auth = instance.auth
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      first <- auth.getChallenge(cookie)
      _ <- auth.submitPhone(cookie, s.phone.get, first.csrf, Some(ip))
      page <- auth.getChallenge(cookie)
    yield OnPage(authorize, cookie, page)

  /** The check the request from `ip` logged, which is written when the response is done, so it is awaited. */
  private def decisionOf(instance: AntifraudInstance, ip: String): Task[Decision] =
    awaited(s"the receive-http line of the request from $ip")(
      instance.decisions.map(_.find(d => d.ip.contains(ip) && d.path == Path)),
    )

  private def claimsOf(idToken: String): Task[String] =
    ZIO.attempt(String(Base64.getUrlDecoder.decode(idToken.split('.')(1)), StandardCharsets.UTF_8))

  /** Answers the passkey page with the user's passkey, and returns the id token's claims of the sign-in it completes. */
  private def passWithPasskey(instance: AntifraudInstance, user: PasskeyUser, on: OnPage): Task[(TokenResult.Success, String)] =
    val auth = instance.auth
    val s = user.setup
    for
      passkeyPage <- auth.getChallenge(on.cookie).assertStep(ConversationStep.Credential)
      options <- auth.getPasskeyOptions(on.cookie).flatMap(_.body.asString)
      response <- TestAuthenticator.get(user.credential, options, Flows.passkeyOrigin, s.userId)
      code <- auth.submitPasskeyAssertion(on.cookie, response.toJson, passkeyPage.csrf).assertRedirect(auth, on.cookie)
      token <- signedIn(instance, s, on, code)
      claims <- claimsOf(token.idToken.get)
    yield (token, claims)

  private def signedIn(instance: AntifraudInstance, s: Flows.Setup, on: OnPage, code: String): Task[TokenResult.Success] =
    instance.auth.token(
      code,
      on.authorize.verifier,
      clientId = Some(s.clientId),
      clientSecret = Some(s.clientSecret),
      redirectUri = Some(s.redirectUri),
    ).success

  /** Chooses the passkey on the code page `on`, and returns the page that follows. */
  private def choosePasskey(instance: AntifraudInstance, on: OnPage): Task[ChallengeResult] =
    for
      _ <- instance.auth.submitOtpAlternative(on.cookie, "passkey", on.page.csrf)
      next <- instance.auth.getChallenge(on.cookie)
    yield next

  /** The outcomes of the steps the client's events hold. The client's user signed in once with the code to enroll a passkey, before
    * the instances started, and that is in the log too: a case counts what was added since it began (`since`, taken first). */
  private def outcomesOf(clientId: String): Task[List[String]] =
    AntifraudFixture.eventsOf(clientId).map(_.map(_._1))

  /** The outcomes written since `before`, once the writer has caught up enough for `until` to hold of them. */
  private def outcomesSince(clientId: String, before: List[String])(until: Map[String, Int] => Boolean): Task[Map[String, Int]] =
    def counts(outcomes: List[String]) = outcomes.groupMapReduce(identity)(_ => 1)(_ + _)
    val earlier = counts(before)
    awaited(s"the events of $clientId"):
      outcomesOf(clientId).map: now =>
        val added = counts(now).map((outcome, n) => outcome -> (n - earlier.getOrElse(outcome, 0))).filter(_._2 > 0)
        Option.when(until(added))(added)

  /** Seeds `entries` through central's admin API and waits until `instance` enforces them: a deny entry of a network nobody
    * else uses goes last, so that a reload that sees it has every entry made before it, and is answered by a request of
    * another setup from that network. */
  private def seededThroughApi(instance: AntifraudInstance, probe: Flows.Setup)(entries: ListEntry*): ZIO[CentralApi, Throwable, Unit] =
    val bits = UUID.randomUUID().getLeastSignificantBits
    val network = s"10.${(bits >>> 8) & 0xff}.${bits & 0xff}"
    for
      central <- ZIO.service[CentralApi]
      until <- Clock.instant.map(_.plusSeconds(3600))
      _ <- AntifraudFixture.seedThroughApi(
        central,
        // An hour is as long as these cases need, and an entry that expires is not left on the lists of the stack.
        entries.toList.map(_.copy(expiresAt = Some(until))) :+ ListEntry("deny", "cidr", s"$network.0/24", expiresAt = Some(until)),
      )
      // Each poll comes from another address of the network (so that the limit on an address does not refuse the polls
      // before the entry is there to), and reads the line of its own request.
      _ <- awaited("the lists to be reloaded", timeout = 30.seconds, every = 500.millis):
        ZIO.suspendSucceed:
          val ip = s"$network.${1 + scala.util.Random.nextInt(250)}"
          enterNumber(instance, probe, ip) *> decisionOf(instance, ip).map(d => Option.when(d.rules.contains("deny_list:cidr"))(()))
    yield ()

  def spec = suite("The passkey in place of the code")(
    test("a user who has a passkey and is asked for a code is offered it, chooses it, and signs in with it") {
      for
        f <- ZIO.service[AntifraudFixture]
        user <- f.nextPasskeyUser
        ip = randomIp()
        on <- enterNumber(f.a, user.setup, ip)
        decision <- decisionOf(f.a, ip)
        passkeyPage <- choosePasskey(f.a, on)
        (token, claims) <- passWithPasskey(f.a, user, on)
        userinfo <- f.a.auth.userinfo(token.accessToken).success
      yield assertTrue(
        decision.outcome.contains("sent"),
        on.page.step.contains(ConversationStep.Otp),
        on.page.html.contains(PasskeyOffered),
        // The passkey page asks for nothing but the user's own passkey, and can go back to the code.
        passkeyPage.step.contains(ConversationStep.Credential),
        passkeyPage.html.contains("\"passkeyOnly\":true"),
        passkeyPage.html.contains("\"backToCode\":true"),
        userinfo.sub == user.setup.userId,
        // What the passkey step records when it is passed.
        claims.contains("swk") || claims.contains("hwk"),
        claims.contains("\"user\""),
      )
    },
    test("going back to the code keeps what was sent: the code still passes, no second code is sent, and the switch is in the event log") {
      for
        f <- ZIO.service[AntifraudFixture]
        user <- f.nextPasskeyUser
        s = user.setup
        auth = f.a.auth
        before <- outcomesOf(s.clientId)
        on <- enterNumber(f.a, s, randomIp())
        passkeyPage <- choosePasskey(f.a, on)
        _ <- auth.submitCodeInstead(on.cookie, passkeyPage.csrf)
        back <- auth.getChallenge(on.cookie)
        code <- auth.submitOtp(on.cookie, "123456", back.csrf).assertRedirect(auth, on.cookie)
        token <- signedIn(f.a, s, on, code)
        added <- outcomesSince(s.clientId, before)(_.get("verified").exists(_ >= 1))
      yield assertTrue(
        back.step.contains(ConversationStep.Otp),
        back.html.contains(PasskeyOffered),
        token.idToken.isDefined,
        // One code was sent, for the first request, and the switch and the return sent none; the switch is in the log.
        added == Map("sent" -> 1, "passkey_alternative" -> 1, "verified" -> 1),
      )
    },
    test("a number on the fake list gets the code page with nothing sent, and a user with a passkey still signs in with it") {
      for
        f <- ZIO.service[AntifraudFixture]
        user <- f.nextPasskeyUser
        probe <- f.nextSetup
        _ <- seededThroughApi(f.a, probe)(ListEntry("fake", "phone_hash", "", phone = user.setup.phone))
        ip = randomIp()
        on <- enterNumber(f.a, user.setup, ip)
        decision <- decisionOf(f.a, ip)
        _ <- choosePasskey(f.a, on)
        (token, _) <- passWithPasskey(f.a, user, on)
        userinfo <- f.a.auth.userinfo(token.accessToken).success
      yield assertTrue(
        decision.decision == "Fake",
        decision.outcome.contains("faked"),
        // The page is the code page, as it is of a send, and offers what any code page of this user offers.
        on.page.step.contains(ConversationStep.Otp),
        on.page.html.contains(PasskeyOffered),
        userinfo.sub == user.setup.userId,
      )
    },
    test("a number on the deny list gets the code page with nothing sent when its user has a passkey, and the access page when not") {
      for
        f <- ZIO.service[AntifraudFixture]
        user <- f.nextPasskeyUser
        without <- f.nextUserWithoutPasskey
        probe <- f.nextSetup
        _ <- seededThroughApi(f.a, probe)(
          ListEntry("deny", "phone_hash", "", phone = user.setup.phone),
          ListEntry("deny", "phone_hash", "", phone = without.phone),
        )
        before <- outcomesOf(user.setup.clientId)
        withIp = randomIp()
        withoutIp = randomIp()
        onWith <- enterNumber(f.a, user.setup, withIp)
        decisionWith <- decisionOf(f.a, withIp)
        onWithout <- enterNumber(f.a, without, withoutIp)
        decisionWithout <- decisionOf(f.a, withoutIp)
        (token, _) <- choosePasskey(f.a, onWith) *> passWithPasskey(f.a, user, onWith)
        userinfo <- f.a.auth.userinfo(token.accessToken).success
        added <- outcomesSince(user.setup.clientId, before)(_.contains("passkey_alternative"))
      yield assertTrue(
        decisionWith.decision == "Block",
        decisionWith.outcome.contains("blocked"),
        onWith.page.step.contains(ConversationStep.Otp),
        onWith.page.html.contains(PasskeyOffered),
        userinfo.sub == user.setup.userId,
        // What the event log says is the decision that was taken, and then the switch.
        added == Map("blocked" -> 1, "passkey_alternative" -> 1),
        decisionWithout.decision == "Block",
        onWithout.page.step.contains(ConversationStep.AccessDenied),
      )
    },
    test("a user without a passkey is offered nothing, and a choice made up for them changes nothing") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextUserWithoutPasskey
        auth = f.a.auth
        on <- enterNumber(f.a, s, randomIp())
        forged <- auth.submitOtpAlternative(on.cookie, "passkey", on.page.csrf)
        after <- auth.getChallenge(on.cookie)
        code <- auth.submitOtp(on.cookie, "123456", after.csrf).assertRedirect(auth, on.cookie)
        token <- signedIn(f.a, s, on, code)
      yield assertTrue(
        on.page.step.contains(ConversationStep.Otp),
        on.page.html.contains(NothingOffered),
        // Nothing is moved: the conversation is on the code step still, and the code passes.
        forged.response.status == Status.SeeOther,
        after.step.contains(ConversationStep.Otp),
        token.idToken.isDefined,
      )
    },
    test("a passkey the user has is not offered when the client's flow cannot ask for one") {
      for
        f <- ZIO.service[AntifraudFixture]
        user <- f.nextPasskeyUserOfFlowWithoutPasskey
        auth = f.a.auth
        on <- enterNumber(f.a, user.setup, randomIp())
        forged <- auth.submitOtpAlternative(on.cookie, "passkey", on.page.csrf)
        after <- auth.getChallenge(on.cookie)
      yield assertTrue(
        on.page.step.contains(ConversationStep.Otp),
        on.page.html.contains(NothingOffered),
        forged.response.status == Status.SeeOther,
        after.step.contains(ConversationStep.Otp),
      )
    },
    test("a person who is registering is offered nothing: what they have proved is the number") {
      for
        f <- ZIO.service[AntifraudFixture]
        s = f.registration
        auth = f.a.auth
        on <- enterNumber(f.a, s, randomIp())
        forged <- auth.submitOtpAlternative(on.cookie, "passkey", on.page.csrf)
        after <- auth.getChallenge(on.cookie)
      yield assertTrue(
        on.page.step.contains(ConversationStep.Otp),
        on.page.html.contains(NothingOffered),
        forged.response.status == Status.SeeOther,
        after.step.contains(ConversationStep.Otp),
      )
    },
    test("a choice with the wrong CSRF value is refused, and the conversation stays on the code step") {
      for
        f <- ZIO.service[AntifraudFixture]
        user <- f.nextPasskeyUser
        auth = f.a.auth
        on <- enterNumber(f.a, user.setup, randomIp())
        refused <- auth.submitOtpAlternative(on.cookie, "passkey", "not-the-token")
        unknown <- auth.submitOtpAlternative(on.cookie, "telegram", on.page.csrf)
        after <- auth.getChallenge(on.cookie)
      yield assertTrue(
        refused.response.status == Status.BadRequest,
        unknown.response.status == Status.BadRequest,
        after.step.contains(ConversationStep.Otp),
      )
    },
    test("the going back and forth is bounded: after the fifth switch the code page offers nothing, and a sixth is refused") {
      for
        f <- ZIO.service[AntifraudFixture]
        user <- f.nextPasskeyUser
        auth = f.a.auth
        on <- enterNumber(f.a, user.setup, randomIp())
        pages <- ZIO.foreach(1 to 5): _ =>
          for
            current <- auth.getChallenge(on.cookie)
            passkeyPage <- choosePasskey(f.a, on.copy(page = current))
            _ <- auth.submitCodeInstead(on.cookie, passkeyPage.csrf)
            back <- auth.getChallenge(on.cookie)
          yield (current, passkeyPage, back)
        exhausted = pages.last._3
        sixth <- auth.submitOtpAlternative(on.cookie, "passkey", exhausted.csrf)
        after <- auth.getChallenge(on.cookie)
        code <- auth.submitOtp(on.cookie, "123456", after.csrf).assertRedirect(auth, on.cookie)
      yield assertTrue(
        pages.forall(_._1.html.contains(PasskeyOffered)),
        pages.forall(_._2.step.contains(ConversationStep.Credential)),
        pages.init.forall(_._3.html.contains(PasskeyOffered)),
        exhausted.step.contains(ConversationStep.Otp),
        exhausted.html.contains(NothingOffered),
        sixth.response.status == Status.SeeOther,
        after.step.contains(ConversationStep.Otp),
        code.nonEmpty,
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.minutes)

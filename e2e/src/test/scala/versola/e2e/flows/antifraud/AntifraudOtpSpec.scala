package versola.e2e.flows.antifraud

import versola.e2e.support.AntifraudFixture.ListEntry
import versola.e2e.support.AntifraudInstance.Decision
import versola.e2e.support.{*, given}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** The antifraud decision of an OTP request, over HTTP, on two auth instances sharing one database.
  *
  * The unit specs prove the rules against stubs; this proves them through the wiring: the `antifraud` config block,
  * the velocity counters' flush and load through Postgres between instances, the lists' reload from `af_list_entry`,
  * the address read from the tenant's IP header, and whether a code goes out. Each case reads the decision and the outcome (`sent`, `faked`, `blocked`) from the
  * request's own `receive-http` line (`antifraud` key): outside production auth calls no SMS provider, so the outcome
  * is where a send is recorded, at the place it would be made.
  *
  * The fixture's limits: ten codes to a number and four requests from an address in an hour (see [[AntifraudFixture]]).
  * Every case has a number of its own and addresses it picked at random, so neither a rerun nor another case counts
  * against it.
  *
  * Lists are seeded in SQL (as the repository stores them) because no endpoint changes them yet.
  */
object AntifraudOtpSpec extends ZIOSpec[AntifraudFixture & E2EConfig & Client & OAuthClient]:

  override val bootstrap: ZLayer[Any, Any, AntifraudFixture & E2EConfig & Client & OAuthClient] =
    val clients = (E2EConfig.live ++ Client.default) >+> OAuthClient.live
    clients >+> AntifraudFixture.layer

  private val Path = "/challenge/phone"

  /** What one request for a code came to: the step the conversation is on afterwards and the check that was logged. */
  private case class Attempt(step: Option[ConversationStep], decision: Decision):
    def refused: Boolean = step.contains(ConversationStep.AccessDenied)
    def answeredWithCodeStep: Boolean = step.contains(ConversationStep.Otp)

  /** An address of its own, so that the line of a request can be told from the others and no count is shared. */
  private def randomIp(): String =
    val bits = UUID.randomUUID().getLeastSignificantBits
    s"10.${(bits >>> 16) & 0xff}.${(bits >>> 8) & 0xff}.${(bits & 0xff).max(3)}"

  /** Waits until `probe` answers, which is what an asynchronous effect (a log line, a reload) is awaited with. */
  private def awaited[A](what: String, timeout: Duration = 20.seconds, every: Duration = 200.millis)(probe: Task[Option[A]]): Task[A] =
    def loop: Task[A] =
      probe.flatMap:
        case Some(found) => ZIO.succeed(found)
        case None => ZIO.sleep(every) *> loop
    loop.timeoutFail(RuntimeException(s"timed out waiting for $what"))(timeout)

  private def request(instance: AntifraudInstance, s: Flows.Setup, ip: String): Task[Option[ConversationStep]] =
    val auth = instance.auth
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie)
      _ <- auth.submitPhone(cookie, s.phone.get, challenge.csrf, Some(ip))
      next <- auth.getChallenge(cookie)
    yield next.step

  private def logged(instance: AntifraudInstance, ip: String): Task[List[Decision]] =
    instance.decisions.map(_.filter(d => d.ip.contains(ip) && d.path == Path))

  /** Asks `instance` for a code for the setup's number from `ip`, and returns the answer with the check the request
    * logged (the line is written when the response is done, so it is awaited). */
  private def attempt(instance: AntifraudInstance, s: Flows.Setup, ip: String): Task[Attempt] =
    for
      before <- logged(instance, ip).map(_.size)
      step <- request(instance, s, ip)
      decision <- awaited(s"the receive-http line of the request from $ip")(logged(instance, ip).map(_.drop(before).headOption))
    yield Attempt(step, decision)

  /** Asks `instance` until a request is refused, at most `max` times, a pause between them. */
  private def untilRefused(instance: AntifraudInstance, s: Flows.Setup, max: Int): Task[(Int, Option[Attempt])] =
    ZIO.iterate((0, Option.empty[Attempt]))(state => state._2.isEmpty && state._1 < max)((n, _) =>
      ZIO.sleep(700.millis) *> attempt(instance, s, randomIp()).map(a => (n + 1, Option.when(a.refused)(a))),
    )

  /** Seeds `entries` and waits until `instance` enforces them, which a deny entry of a network nobody else uses (added
    * in the same transaction, so in the same snapshot) says: a request from it is refused by that entry. Every poll
    * comes from another address of the network, so that the address limit does not refuse the polls before the entry
    * is there to. */
  private def seeded(instance: AntifraudInstance, s: Flows.Setup)(entries: ListEntry*): Task[Unit] =
    val bits = UUID.randomUUID().getLeastSignificantBits
    val network = s"10.${(bits >>> 8) & 0xff}.${bits & 0xff}"
    AntifraudFixture.seed(ListEntry("deny", "cidr", s"$network.0/24") :: entries.toList) *>
      awaited("the lists to be reloaded", timeout = 30.seconds, every = 500.millis)(
        attempt(instance, s, s"$network.${1 + scala.util.Random.nextInt(250)}").map(a => Option.when(a.decision.rules.contains("deny_list:cidr"))(())),
      )

  private def velocitySuite = suite("velocity across instances")(
    test("the limit on a number fires on the other instance after flush and load, and a refused request sends no code") {
      val limit = AntifraudFixture.PhoneLimitPerHour
      // Six requests in, each instance holds three. A request is refused when the count with it in exceeds the limit.
      // With what the other instance flushed loaded that is the 5th probe; alone, the instance gets there only at the 8th.
      val probes = limit - 3
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        // Alternating: neither instance holds more than half of the limit, so each lets all of them through.
        firsts <- ZIO.foreach(List(f.a, f.b, f.a, f.b, f.a, f.b))(attempt(_, s, randomIp()))
        // Whatever B is asked is counted (and let through) until B has loaded what A flushed. The probes go on, spaced
        // by more than a flush interval, until one is refused.
        fromB <- untilRefused(f.b, s, probes)
        // A learns of B's requests the same way.
        onA <- untilRefused(f.a, s, probes)
        (probed, refusal) = fromB
      yield assertTrue(
        firsts.forall(a =>
          a.answeredWithCodeStep && a.decision.decision == "Allow" && a.decision.rules.isEmpty && a.decision.outcome.contains("sent"),
        ),
      ) &&
        assertTrue(refusal.isDefined).label("B must refuse a request within the probes: the count of A reached it") &&
        assertTrue(probed < probes).label("B alone would have refused only after more probes than were made") &&
        assertTrue(
          refusal.exists(_.decision.decision == "Block"),
          refusal.exists(_.decision.rules.contains("velocity_phone_1h")),
          refusal.exists(_.decision.outcome.contains("blocked")),
        ).label("the refused request sends no code") &&
        assertTrue(onA._2.exists(_.decision.rules.contains("velocity_phone_1h"))).label("A must refuse too, from B's count")
    },
    test("a request refused for its address does not raise the count of its number") {
      val ipLimit = AntifraudFixture.IpLimitPerHour
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        busy = randomIp()
        within <- ZIO.foreach(List.range(0, ipLimit))(_ => attempt(f.a, s, busy))
        // Over the address's limit, and so refused. Counted, they would push the number far over its own limit (ten).
        refused <- ZIO.foreach(List.range(0, AntifraudFixture.PhoneLimitPerHour))(_ => attempt(f.a, s, busy))
        // The same number from an address that has no count: it has ipLimit requests counted, not more.
        other <- attempt(f.a, s, randomIp())
      yield assertTrue(
        within.forall(a => a.answeredWithCodeStep && a.decision.decision == "Allow" && a.decision.outcome.contains("sent")),
        refused.forall(a =>
          a.refused && a.decision.decision == "Block" && a.decision.rules == List("velocity_ip_1h") && a.decision.outcome.contains("blocked"),
        ),
        other.answeredWithCodeStep,
        other.decision.decision == "Allow",
        other.decision.outcome.contains("sent"),
      )
    },
  )

  private def listsSuite = suite("lists over HTTP")(
    test("a deny entry refuses the request on both instances and sends no code") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        _ <- seeded(f.a, s)(ListEntry("deny", "ip", ip))
        onA <- attempt(f.a, s, ip)
        onB <- awaited("instance B to reload the lists")(attempt(f.b, s, ip).map(a => Option.when(a.refused)(a)))
      yield assertTrue(
        onA.refused,
        onA.decision.decision == "Block",
        onA.decision.rules == List("deny_list:ip"),
        onA.decision.outcome.contains("blocked"),
        onB.decision.outcome.contains("blocked"),
        onB.decision.rules == List("deny_list:ip"),
      )
    },
    test("a fake entry answers like a send and sends nothing, where the same number from another address gets a code") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        _ <- seeded(f.a, s)(ListEntry("fake", "ip", ip))
        faked <- attempt(f.a, s, ip)
        control <- attempt(f.a, s, randomIp())
      yield assertTrue(
        faked.answeredWithCodeStep,
        faked.decision.decision == "Fake",
        faked.decision.rules == List("fake_list:ip"),
        faked.decision.outcome.contains("faked"),
        control.answeredWithCodeStep,
        control.decision.decision == "Allow",
        control.decision.outcome.contains("sent"),
      )
    },
    test("an allow entry does not beat a deny entry of the same address") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        _ <- seeded(f.a, s)(ListEntry("allow", "ip", ip), ListEntry("deny", "ip", ip))
        denied <- attempt(f.a, s, ip)
      yield assertTrue(
        denied.refused,
        denied.decision.decision == "Block",
        denied.decision.rules.contains("deny_list:ip"),
        denied.decision.outcome.contains("blocked"),
      )
    },
    test("an allowed address inside a denied network is let through, and another address of the network is refused") {
      val bits = UUID.randomUUID().getLeastSignificantBits
      val network = s"10.${(bits >>> 8) & 0xff}.${bits & 0xff}"
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        _ <- seeded(f.a, s)(ListEntry("deny", "cidr", s"$network.0/24"), ListEntry("allow", "ip", s"$network.7"))
        allowed <- attempt(f.a, s, s"$network.7")
        denied <- attempt(f.a, s, s"$network.8")
      yield assertTrue(
        allowed.answeredWithCodeStep,
        allowed.decision.decision == "Allow",
        allowed.decision.rules == List("allow_list:ip"),
        allowed.decision.outcome.contains("sent"),
        denied.refused,
        denied.decision.rules == List("deny_list:cidr"),
        denied.decision.outcome.contains("blocked"),
      )
    },
    test("a deny entry stops refusing once it has expired") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        until <- Clock.instant.map(_.plusSeconds(10))
        _ <- seeded(f.a, s)(ListEntry("deny", "ip", ip, expiresAt = Some(until)))
        before <- attempt(f.a, s, ip)
        // The entry stays in the snapshot until the next reload, but the lookup ignores it once its time has passed.
        after <- awaited("the entry to expire", timeout = 40.seconds, every = 1.second)(
          attempt(f.a, s, ip).map(a => Option.when(!a.refused)(a)),
        )
        now <- Clock.instant
      yield assertTrue(
        before.refused,
        before.decision.rules == List("deny_list:ip"),
        before.decision.outcome.contains("blocked"),
        !now.isBefore(until),
        after.answeredWithCodeStep,
        after.decision.decision == "Allow",
        after.decision.outcome.contains("sent"),
      )
    },
    test("a shadow entry is matched and does not enforce") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        _ <- seeded(f.a, s)(ListEntry("deny", "ip", ip, shadow = true))
        shadowed <- attempt(f.a, s, ip)
      yield assertTrue(
        shadowed.answeredWithCodeStep,
        shadowed.decision.decision == "Allow",
        shadowed.decision.rules == List("shadow_list:ip"),
        shadowed.decision.outcome.contains("sent"),
      )
    },
  )

  /** What a page that can answer a challenge sends: `proof_inline`, and on the second turn the token and its solution. */
  private val Inline = Map("proof_inline" -> "1")

  /** Finds the number the page would: SHA-256 of `seed:number` with `difficulty` leading zero bits. */
  private def solved(seed: String, difficulty: Int): String =
    def zeroBits(digest: Array[Byte]): Int =
      val first = digest.indexWhere(_ != 0)
      if first < 0 then digest.length * 8 else first * 8 + Integer.numberOfLeadingZeros(digest(first) & 0xff) - 24
    Iterator.from(0).map(_.toString).find: nonce =>
      zeroBits(java.security.MessageDigest.getInstance("SHA-256").nn.digest(s"$seed:$nonce".getBytes("UTF-8")).nn) >= difficulty
    .get

  /** The answer to a request sent as the page does: the JSON of the inline shape. */
  private def inlineAnswer(result: SubmitResult): Task[Json.Obj] =
    result.response.body.asString.flatMap(body => ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_)))

  /** The challenge in an inline answer, as the fields that answer it: the token and its solution. */
  private def answerOf(answer: Json.Obj): Task[Map[String, String]] =
    ZIO.fromOption(for
      challenge <- answer.obj("challenge")
      token <- challenge.str("token")
      params <- challenge.obj("params")
      seed <- params.str("seed")
      difficulty <- params.str("difficulty").flatMap(_.toIntOption)
    yield Map("proof_token" -> token, "proof_solution" -> solved(seed, difficulty))).orElseFail(RuntimeException(s"no challenge in $answer"))

  /** The decision a request logged on `path` from `ip`, the `nth` (from 0) such request. */
  private def line(instance: AntifraudInstance, ip: String, path: String, nth: Int): Task[Decision] =
    awaited(s"the receive-http line of request $nth to $path from $ip")(
      instance.decisions.map(_.filter(d => d.ip.contains(ip) && d.path == path).drop(nth).headOption),
    )

  private def challengeSuite = suite("challenge over HTTP")(
    test("a request that can answer is asked for a proof and sent nothing; the same request with the solution sends the code once") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        _ <- seeded(f.a, s)(ListEntry("watch", "ip", ip))
        auth = f.a.auth
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        csrf <- auth.getChallenge(cookie).map(_.csrf)
        asked <- auth.submitPhoneWith(cookie, s.phone.get, csrf, Some(ip), Inline).flatMap(inlineAnswer)
        stepAfterAsk <- auth.getChallenge(cookie).map(_.step)
        askedLine <- line(f.a, ip, Path, 0)
        answer <- answerOf(asked)
        answered <- auth.submitPhoneWith(cookie, s.phone.get, csrf, Some(ip), Inline ++ answer).flatMap(inlineAnswer)
        stepAfterAnswer <- auth.getChallenge(cookie).map(_.step)
        answeredLine <- line(f.a, ip, Path, 1)
        // The code is sent: the token is spent, and a resend is asked for a proof again, whatever it presents.
        replay <- auth.submitOtpResendWith(cookie, csrf, Some(ip), Inline ++ answer).flatMap(inlineAnswer)
      yield assertTrue(
        asked.obj("challenge").flatMap(_.str("kind")).contains("pow"),
        asked.str("next").isEmpty,
        stepAfterAsk.contains(ConversationStep.Credential),
        askedLine.decision == "Challenge",
        askedLine.rules.contains("watch_list:ip"),
        askedLine.outcome.contains("challenged"),
        answered.str("next").isDefined,
        stepAfterAnswer.contains(ConversationStep.Otp),
        answeredLine.decision == "Challenge",
        answeredLine.outcome.contains("sent"),
        replay.obj("challenge").isDefined,
        replay.str("next").isEmpty,
      )
    },
    test("a request that cannot answer is left on the code step with nothing sent, and asks for the code there as a resend") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        _ <- seeded(f.a, s)(ListEntry("watch", "ip", ip))
        auth = f.a.auth
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        csrf <- auth.getChallenge(cookie).map(_.csrf)
        _ <- auth.submitPhone(cookie, s.phone.get, csrf, Some(ip))
        plainStep <- auth.getChallenge(cookie).map(_.step)
        plainLine <- line(f.a, ip, Path, 0)
        // The page of that step asks for the code as a resend, from script.
        asked <- auth.submitOtpResendWith(cookie, csrf, Some(ip), Inline).flatMap(inlineAnswer)
        answer <- answerOf(asked)
        answered <- auth.submitOtpResendWith(cookie, csrf, Some(ip), Inline ++ answer).flatMap(inlineAnswer)
        askedLine <- line(f.a, ip, "/challenge/otp/resend", 0)
        sentLine <- line(f.a, ip, "/challenge/otp/resend", 1)
      yield assertTrue(
        plainStep.contains(ConversationStep.Otp),
        plainLine.decision == "Challenge",
        plainLine.outcome.contains("challenged"),
        asked.obj("challenge").isDefined,
        askedLine.outcome.contains("challenged"),
        answered.str("next").isDefined,
        sentLine.outcome.contains("sent"),
      )
    },
    test("login_hint is answered the same way: the request is left on the code step, and the code is sent on the resend's solution") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        _ <- seeded(f.a, s)(ListEntry("watch", "ip", ip))
        auth = f.a.auth
        authorize <- auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, loginHint = s.phone, ip = Some(ip))
        cookie = authorize.conversationCookie.get
        challenged <- auth.getChallenge(cookie)
        hintLine <- line(f.a, ip, "/authorize", 0)
        asked <- auth.submitOtpResendWith(cookie, challenged.csrf, Some(ip), Inline).flatMap(inlineAnswer)
        answer <- answerOf(asked)
        answered <- auth.submitOtpResendWith(cookie, challenged.csrf, Some(ip), Inline ++ answer).flatMap(inlineAnswer)
        sentLine <- line(f.a, ip, "/challenge/otp/resend", 1)
      yield assertTrue(
        challenged.step.contains(ConversationStep.Otp),
        hintLine.decision == "Challenge",
        hintLine.outcome.contains("challenged"),
        answered.str("next").isDefined,
        sentLine.outcome.contains("sent"),
      )
    },
    test("a challenged send takes one step of the limit on codes for the number, on the credential form and on the pending step") {
      for
        f <- ZIO.service[AntifraudFixture]
        direct <- f.nextSetup
        viaPending <- f.nextSetup
        // The polls that wait for the entries to be loaded are requests for a number too, and take steps of its limit:
        // they use a number of their own, so that the two under test start with none.
        probe <- f.nextSetup
        directIp = randomIp()
        pendingIp = randomIp()
        _ <- seeded(f.a, probe)(ListEntry("watch", "ip", directIp), ListEntry("watch", "ip", pendingIp))
        auth = f.a.auth
        before <- AntifraudFixture.otpRequestSteps(direct.phone.get) <*> AntifraudFixture.otpRequestSteps(viaPending.phone.get)
        // The credential form: asked, then answered.
        started <- auth.authorize(clientId = Some(direct.clientId), redirectUri = Some(direct.redirectUri)).assertChallengeRedirect
        cookie = started.conversationCookie.get
        csrf <- auth.getChallenge(cookie).map(_.csrf)
        asked <- auth.submitPhoneWith(cookie, direct.phone.get, csrf, Some(directIp), Inline).flatMap(inlineAnswer)
        _ <- answerOf(
          asked,
        ).flatMap(answer => auth.submitPhoneWith(cookie, direct.phone.get, csrf, Some(directIp), Inline ++ answer)).flatMap(inlineAnswer)
        directSteps <- AntifraudFixture.otpRequestSteps(direct.phone.get)
        // The step a plain post leaves: the post, the page's own ask and its answer.
        pendingStart <- auth.authorize(clientId = Some(viaPending.clientId), redirectUri = Some(viaPending.redirectUri)).assertChallengeRedirect
        pendingCookie = pendingStart.conversationCookie.get
        pendingCsrf <- auth.getChallenge(pendingCookie).map(_.csrf)
        _ <- auth.submitPhone(pendingCookie, viaPending.phone.get, pendingCsrf, Some(pendingIp))
        pendingAsked <- auth.submitOtpResendWith(pendingCookie, pendingCsrf, Some(pendingIp), Inline).flatMap(inlineAnswer)
        sent <- answerOf(pendingAsked).flatMap(answer =>
          auth.submitOtpResendWith(pendingCookie, pendingCsrf, Some(pendingIp), Inline ++ answer),
        ).flatMap(inlineAnswer)
        pendingSteps <- AntifraudFixture.otpRequestSteps(viaPending.phone.get)
      yield assertTrue(
        before == (0, 0),
        asked.obj("challenge").isDefined,
        directSteps == 1,
        sent.bool("otp").contains(true),
        pendingSteps == 1,
      )
    },
    test("a wrong solution is asked again, and the conversation stays where it was") {
      for
        f <- ZIO.service[AntifraudFixture]
        s <- f.nextSetup
        ip = randomIp()
        _ <- seeded(f.a, s)(ListEntry("watch", "ip", ip))
        auth = f.a.auth
        wrong <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = wrong.conversationCookie.get
        csrf <- auth.getChallenge(cookie).map(_.csrf)
        asked <- auth.submitPhoneWith(cookie, s.phone.get, csrf, Some(ip), Inline).flatMap(inlineAnswer)
        token <- ZIO.fromOption(asked.obj("challenge").flatMap(_.str("token"))).orElseFail(RuntimeException("no token"))
        again <- auth.submitPhoneWith(
          cookie,
          s.phone.get,
          csrf,
          Some(ip),
          Inline ++ Map("proof_token" -> token, "proof_solution" -> "not-a-number"),
        ).flatMap(inlineAnswer)
        step <- auth.getChallenge(cookie).map(_.step)
      yield assertTrue(again.obj("challenge").isDefined, step.contains(ConversationStep.Credential))
    },
  )

  def spec = suite("Antifraud decision of an OTP request")(velocitySuite, listsSuite, challengeSuite) @@
    TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.minutes)

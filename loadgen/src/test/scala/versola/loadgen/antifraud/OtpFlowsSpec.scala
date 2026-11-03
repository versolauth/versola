package versola.loadgen.antifraud

import versola.loadgen.config.TargetsConfig
import versola.loadgen.protocol.{ClientCreds, ClientRegistration, ClientRegistry, HttpAuthClient}
import zio.http.*
import zio.test.*
import zio.{Duration, UIO, ZIO, durationInt}

import java.util.SplittableRandom

/** The request for a code as each kind of client makes it, and the scenarios over several instances, against a stand-in
  * for `auth` ([[AuthSim]]). */
object OtpFlowsSpec extends ZIOSpecDefault:

  private val registry = ClientRegistry(
    Map("mobile-otp" -> ClientRegistration(ClientCreds("mobile-otp", None), "app://callback")),
    "mobile-otp",
  )

  private val plan = NumberPlanConfig(dialCode = "+49", nationalLength = 11, leads = List("151", "162"), rangeLength = 6)

  private def config(
      client: OtpClient = OtpClient.Script,
      challenge: ChallengeClientConfig = ChallengeClientConfig(pause = Duration.Zero, pauseSlack = 1.milli),
      metrics: List[String] = List("http://a.test/i1/metrics"),
      scenarios: List[AntifraudScenario] = Nil,
      fakeTiming: FakeTimingConfig = FakeTimingConfig(),
  ): AntifraudConfig =
    AntifraudConfig(
      label = "test",
      clientId = "mobile-otp",
      redirectUri = "app://callback",
      scope = "openid",
      authMetricsUrl = "http://a.test/i1/metrics",
      reportPath = "unused.json",
      baselinePath = None,
      scenarios = scenarios,
      otp = OtpDeliveryConfig(source = OtpSource.Stub, length = 6, client = client),
      smsStub = SmsStubConfig(bindHost = "0.0.0.0", port = 0, responseDelay = Duration.Zero),
      numbers = plan,
      normal = NormalTrafficConfig(rate = 10.0, duration = 1.second, conversion = 1.0, refreshesMin = 1, refreshesMax = 1),
      prefixAttack = AttackConfig(rate = 40.0, duration = 1.second),
      deviceAttack = AttackConfig(rate = 10.0, duration = 1.second),
      distributedAttack = AttackConfig(rate = 10.0, duration = 1.second),
      tokenControl = TokenControlConfig(sessions = 1, rate = 1.0, duration = 1.second),
      mixed = MixedConfig(normalRate = 1.0, attackRate = 1.0, duration = 1.second, legitInRangeShare = 0.1),
      mimicking = MimickingConfig(rate = 1.0, duration = 1.second, verifyShare = 0.3),
      targeted = AttackConfig(rate = 1.0, duration = 1.second),
      guessing = GuessingConfig(rate = 1.0, duration = 1.second, attempts = 1),
      attackSmsCeiling = 0.1,
      legitRefusalCeiling = 0.01,
      maxInFlight = 64,
      warmupSessions = 0,
      queueSampleEvery = 20.millis,
      controlTolerance = 5.millis,
      addedLatencyCeiling = 10.millis,
      seed = Some(1L),
      sources = SourcesConfig(header = "X-Forwarded-For", legit = 10, distributed = 5),
      authMetricsUrls = metrics,
      challenge = challenge,
      fakeTiming = fakeTiming,
    )

  /** A client for each of the instances `names`, which the sim answers as under `/<name>`. */
  private def clientsFor(sim: AuthSim, names: List[String]) =
    for
      _ <- TestClient.addRoutes(sim.routesAt(names*))
      client <- ZIO.service[Client]
      auths <- ZIO.foreach(names.toVector)(name =>
        HttpAuthClient.make(client, TargetsConfig(s"http://a.test/$name", "http://e.test", "http://m.test", "o"), registry, 5.seconds),
      )
    yield (client, auths)

  private def flowsOver(auth: HttpAuthClient, cfg: AntifraudConfig, stub: SmsStub, measurements: Measurements, tally: Tally): UIO[OtpFlows] =
    PowSolver.make(2).map(solver => OtpFlows(auth, cfg, stub, measurements, tally, solver))

  private def requestWith(sim: AuthSim, cfg: AntifraudConfig, stub: SmsStub, phone: String = "+4915112345678") =
    for
      (_, auths) <- clientsFor(sim, List("i1"))
      measurements = Measurements()
      tally = Tally()
      flows <- flowsOver(auths.head, cfg, stub, measurements, tally)
      requested <- flows.requestOtp(phone)
    yield (requested, tally.snapshot, measurements)

  private def stubFor = SmsStub(codeLength = 6, rangeLength = 6, responseDelay = Duration.Zero)

  def spec = suite("OtpFlows and Scenarios against a stand-in for auth")(
    suite("a client with script")(
      test("answers a proof of work and is sent the code: challenged and solved, not refused") {
        val stub = stubFor
        for
          sim <- AuthSim.make(stub, difficulty = Some(12))
          (requested, outcomes, measurements) <- requestWith(sim, config(), stub)
          calls <- sim.seen
          posts = calls.filter(_.path == "/challenge/phone")
          snapshot = measurements.challenge.snapshot.get
          nonce = posts.last.form("proof_solution")
        yield assertTrue(
          requested.isInstanceOf[OtpFlows.Requested.Sent],
          outcomes.requested == 1 && outcomes.sent == 1 && outcomes.refused == 0 && outcomes.other == 0,
          outcomes.challenged == 1 && outcomes.solved == 1 && outcomes.paidByWaiting == 0,
          // the same request twice, the second with the token and the solution, both sent from script
          posts.size == 2,
          posts.forall(_.form.get("proof_inline").contains("1")),
          !posts.head.form.contains("proof_token"),
          posts.last.form.get("proof_token").exists(_.startsWith("token-")),
          Pow.isSolution(s"seed-${posts.last.conversation}", nonce, 12),
          snapshot.received == 1 && snapshot.solved == 1 && snapshot.unanswered == 0,
          snapshot.hashes >= 1 && snapshot.hashesP50 == snapshot.hashes,
          // the server's time is one sample over both posts, and the person's wait includes the solving
          measurements.quantiles("otp-request").count == 1,
          snapshot.requestTotal.count == 1,
          stub.sent == 1,
        )
      },
      test("a request that is not challenged is one post, and leaves no challenge section") {
        val stub = stubFor
        for
          sim <- AuthSim.make(stub, difficulty = None)
          (requested, outcomes, measurements) <- requestWith(sim, config(), stub)
          posts <- sim.seen.map(_.filter(_.path == "/challenge/phone"))
        yield assertTrue(
          requested.isInstanceOf[OtpFlows.Requested.Sent],
          posts.size == 1,
          outcomes.challenged == 0 && outcomes.solved == 0,
          measurements.challenge.snapshot.isEmpty,
        )
      },
      test("a challenge of a kind it cannot solve is left unanswered: counted as challenged, not as refused or sent") {
        val stub = stubFor
        for
          sim <- AuthSim.make(stub, difficulty = Some(12), kind = "widget")
          (requested, outcomes, measurements) <- requestWith(sim, config(), stub)
        yield assertTrue(
          requested == OtpFlows.Requested.Other("challenge unanswered: kind widget"),
          outcomes.challenged == 1 && outcomes.solved == 0 && outcomes.refused == 0 && outcomes.sent == 0 && outcomes.other == 1,
          measurements.challenge.snapshot.exists(s => s.received == 1 && s.unanswered == 1 && s.solved == 0),
          stub.sent == 0,
        )
      },
      test("a difficulty over what the client will pay is left unanswered, and nothing is solved") {
        val stub = stubFor
        for
          sim <- AuthSim.make(stub, difficulty = Some(40))
          (requested, outcomes, measurements) <- requestWith(sim, config(challenge = ChallengeClientConfig(maxDifficulty = 26)), stub)
        yield assertTrue(
          requested.isInstanceOf[OtpFlows.Requested.Other],
          outcomes.challenged == 1 && outcomes.solved == 0,
          measurements.challenge.snapshot.exists(s => s.unanswered == 1 && s.hashes == 0),
        )
      },
    ),
    suite("a plain client")(
      test("is left pending, waits, asks again, and is sent the code: paid by waiting") {
        val stub = stubFor
        for
          sim <- AuthSim.make(stub, difficulty = Some(12), asksToHonour = 1)
          (requested, outcomes, measurements) <- requestWith(sim, config(client = OtpClient.Plain), stub)
          calls <- sim.seen
        yield assertTrue(
          requested.isInstanceOf[OtpFlows.Requested.Sent],
          outcomes.sent == 1 && outcomes.challenged == 1 && outcomes.paidByWaiting == 1 && outcomes.solved == 0 && outcomes.refused == 0,
          // the post of the phone had no proof field; the asks were plain posts to the resend, the first one early
          calls.filter(_.path == "/challenge/phone").forall(!_.form.contains("proof_inline")),
          calls.count(_.path == "/challenge/otp/resend") == 2,
          measurements.challenge.snapshot.exists(s => s.paused == 1 && s.pauseHonoured == 1 && s.earlyAsks == 1 && s.received == 0),
          measurements.quantiles.contains("otp-request") && measurements.quantiles.contains("otp-resend"),
          stub.sent == 1,
        )
      },
      test("an auth that never honours the ask leaves it pending: counted as challenged and other, after max-asks asks") {
        val stub = stubFor
        for
          sim <- AuthSim.make(stub, difficulty = Some(12), asksToHonour = 100)
          (requested, outcomes, _) <- requestWith(sim, config(client = OtpClient.Plain, challenge = ChallengeClientConfig(pause = Duration.Zero, pauseSlack = 1.milli, maxAsks = 3)), stub)
          asks <- sim.seen.map(_.count(_.path == "/challenge/otp/resend"))
        yield assertTrue(
          requested == OtpFlows.Requested.Other("pending"),
          outcomes.challenged == 1 && outcomes.other == 1 && outcomes.sent == 0 && outcomes.paidByWaiting == 0,
          asks == 3,
          stub.sent == 0,
        )
      },
      test("a plain client that is not challenged does not wait or ask") {
        val stub = stubFor
        for
          sim <- AuthSim.make(stub, difficulty = None)
          (requested, outcomes, _) <- requestWith(sim, config(client = OtpClient.Plain), stub)
          asks <- sim.seen.map(_.count(_.path == "/challenge/otp/resend"))
        yield assertTrue(requested.isInstanceOf[OtpFlows.Requested.Sent], asks == 0, outcomes.challenged == 0)
      },
    ),
    suite("instances")(
      test("sessions are given the instances in turn, and a session stays on the one it began on") {
        val stub = stubFor
        val cfg = config(metrics = List("http://a.test/i1/metrics", "http://a.test/i2/metrics"), scenarios = List(AntifraudScenario.PrefixAttack))
        for
          sim <- AuthSim.make(stub)
          (client, auths) <- clientsFor(sim, List("i1", "i2"))
          solver <- PowSolver.make(1)
          scenarios = Scenarios(client, auths, cfg, stub, PhoneNumbers(plan, SplittableRandom(1L)), SplittableRandom(2L), solver)
          result <- scenarios.run(AntifraudScenario.PrefixAttack)
          calls <- sim.seen
          conversationCalls = calls.filter(_.conversation.nonEmpty).groupBy(_.conversation)
          hostsPerSession = conversationCalls.values.map(_.map(_.host).toSet.size).toList
          perHost = calls.filter(_.path == "/authorize").groupBy(_.host).view.mapValues(_.size).toMap
          metricsHosts = calls.filter(_.path == "/metrics").map(_.host).toSet
        yield assertTrue(
          result.otp.requested == 40,
          hostsPerSession.nonEmpty && hostsPerSession.forall(_ == 1),
          perHost.keySet == Set("i1", "i2"),
          math.abs(perHost("i1") - perHost("i2")) <= 1,
          // the metrics of both instances are read, and summed: 1.0 enqueued each, a delta of zero, a queue of 2 + 2
          metricsHosts == Set("i1", "i2"),
          result.auth.queueSize == 4.0,
        )
      },
    ),
    suite("fake-timing")(
      suite("over a fake list entry")(
        test("a fake send that takes as long as a real one passes") {
          val stub = stubFor
          for
            result <- runFakeTiming(stub, realDelay = 60.millis, fakeDelay = 60.millis, fakePrefix = Some("491599"))
            timing = result.fakeTiming.get
          yield assertTrue(
            timing.passed,
            timing.fake.sent == 25 && timing.real.sent == 25,
            timing.fake.reachedSms == 0 && timing.real.reachedSms == 25,
            math.abs(timing.medianGapMs) < 40.0,
            result.otp.requested == 50,
          )
        },
        test("a fake send that answers 140 ms faster fails on the medians") {
          val stub = stubFor
          for
            result <- runFakeTiming(stub, realDelay = 150.millis, fakeDelay = 10.millis, fakePrefix = Some("491599"))
            timing = result.fakeTiming.get
          yield assertTrue(!timing.passed, timing.medianGapMs < -40.0, timing.problems.exists(_.contains("medians differ")))
        },
        test("a fake entry that was never seeded is caught by what reached the stub, not passed for equal timings") {
          val stub = stubFor
          for
            // the numbers are real as far as the sim goes: they are all sent
            result <- runFakeTiming(stub, realDelay = 10.millis, fakeDelay = 10.millis, fakePrefix = None)
            timing = result.fakeTiming.get
          yield assertTrue(!timing.passed, timing.fake.reachedSms == 25, timing.problems.exists(_.contains("25 of the fake numbers were sent a code")))
        },
      ),
    ),
  ).provide(TestClient.layer) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

  /** 25 + 25 requests at 100 a second against a sim whose fake list is `fakePrefix` (which `fake-timing` is told as its own
    * `fake-prefix`: the sim does not apply it when it is none, as an entry that was not seeded). */
  private def runFakeTiming(stub: SmsStub, realDelay: Duration, fakeDelay: Duration, fakePrefix: Option[String]) =
    val cfg = config(
      scenarios = List(AntifraudScenario.FakeTiming),
      fakeTiming = FakeTimingConfig(requests = 25, rate = 100.0, tolerance = 40.millis, warmup = 4, fakePrefix = Some("491599")),
    )
    for
      sim <- AuthSim.make(stub, fakePrefix = fakePrefix, realDelay = realDelay, fakeDelay = fakeDelay)
      (client, auths) <- clientsFor(sim, List("i1"))
      solver <- PowSolver.make(1)
      scenarios = Scenarios(client, auths, cfg, stub, PhoneNumbers(plan, SplittableRandom(1L)), SplittableRandom(2L), solver)
      result <- scenarios.run(AntifraudScenario.FakeTiming)
    yield result

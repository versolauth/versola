package versola.loadgen.antifraud

import zio.Config
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.json.*
import zio.test.*
import zio.{Duration, durationInt}

import java.nio.file.{Files, Path}
import java.util.SplittableRandom

object AntifraudSpec extends ZIOSpecDefault:

  private val exposition =
    """# HELP antifraud_events_enqueued_total
      |antifraud_events_enqueued_total 120.0 1792697416726
      |antifraud_events_written_total{} 100.0 1792697416726
      |antifraud_events_dropped_total{reason="queue_full"} 3.0 1792697416726
      |antifraud_events_dropped_total{reason="throttled_shed"} 2.0 1792697416726
      |antifraud_sms_budget_refused_total 40.0 1792697416726
      |antifraud_event_queue_size 17.0 1792697416726
      |http_server_requests_total{path="/token"} 999.0
      |""".stripMargin

  private val plan = NumberPlanConfig(dialCode = "+49", nationalLength = 11, leads = List("151", "162"), rangeLength = 6)

  private def quantiles(p99: Double): Quantiles = Quantiles(count = 100, errors = 0, p50Ms = 1.0, p99Ms = p99, maxMs = p99)

  private def result(scenario: String, calls: (String, Double)*): ScenarioResult =
    ScenarioResult(
      scenario = scenario,
      seconds = 1.0,
      arrivals = 1,
      waited = 0,
      latencies = calls.map((call, p99) => call -> quantiles(p99)).toMap,
      otp = OtpOutcomes(requested = 0, sent = 0, refused = 0, other = 0, verified = 0, rejected = 0, failed = 0, notEntered = 0),
      legitInRange = None,
      legitElsewhere = None,
      failures = Map.empty,
      smsSent = 0,
      smsRanges = 0,
      smsBusiestRange = 0,
      auth = AuthAntifraudMetrics.empty,
    )

  private def group(p50: Double, reached: Long, sent: Long): TimingGroup = TimingGroup(sent, sent, 0, 0, reached, p50, p50 + 5, p50 + 10, p50 + 12)

  private val solvedStats: ChallengeSnapshot =
    val stats = ChallengeStats()
    stats.received.increment()
    stats.recordSolve(5000L, 200L)
    stats.snapshot.get

  private def verdictOf(comparisons: List[Comparison], call: String): Verdict = comparisons.find(_.call == call).get.verdict

  def spec = suite("antifraud loadgen")(
    suite("AuthAntifraudMetrics")(
      test("reads the antifraud series of the exposition, with or without labels and a timestamp") {
        val metrics = AuthAntifraudMetrics.parse(exposition)
        assertTrue(
          metrics.enqueued == 120.0,
          metrics.written == 100.0,
          metrics.dropped == Map("queue_full" -> 3.0, "throttled_shed" -> 2.0),
          metrics.droppedTotal == 5.0,
          metrics.smsRefused == 40.0,
          metrics.smsBudgetFull == 0.0,
          metrics.queueSize == 17.0,
        )
      },
      test("an exposition without the antifraud series reads as zeroes") {
        assertTrue(AuthAntifraudMetrics.parse("up 1.0\n") == AuthAntifraudMetrics.empty)
      },
      test("counts are subtracted, the queue size is the later one") {
        val before = AuthAntifraudMetrics.parse(exposition)
        val after = before.copy(enqueued = 150.0, dropped = Map("queue_full" -> 8.0), queueSize = 4.0)
        val delta = after.minus(before)
        assertTrue(delta.enqueued == 30.0, delta.dropped("queue_full") == 5.0, delta.dropped("throttled_shed") == -2.0, delta.queueSize == 4.0)
      },
    ),
    suite("PhoneNumbers")(
      test("a number is in the plan's country, has its length and starts with one of its leads") {
        val numbers = PhoneNumbers(plan, SplittableRandom(1L))
        val sample = List.fill(200)(numbers.any())
        assertTrue(
          sample.forall(_.startsWith("+49")),
          sample.forall(_.length == "+49".length + 11),
          sample.forall(phone => plan.leads.exists(lead => phone.drop(3).startsWith(lead))),
        )
      },
      test("a range is as long as asked, and every number made in it is in it") {
        val numbers = PhoneNumbers(plan, SplittableRandom(2L))
        val range = numbers.anyRange()
        val inside = List.fill(100)(numbers.in(range))
        assertTrue(
          range.length == plan.rangeLength,
          inside.forall(numbers.rangeOf(_) == range),
          inside.forall(_.length == "+49".length + 11),
          inside.distinct.size > 90,
        )
      },
    ),
    suite("SmsStub")(
      test("counts sends per range and keeps the last code of a number") {
        val stub = SmsStub(codeLength = 6, rangeLength = 6, responseDelay = Duration.Zero)
        stub.accept(phone = "+4915112345678", message = "Your code is 123456, do not share it")
        stub.accept(phone = "+4915198765432", message = "Code 654321")
        stub.accept(phone = "+4915112345678", message = "Code 111222 now")
        assertTrue(
          stub.sent == 3L,
          stub.sentByRange == Map("491511" -> 2L, "491519" -> 1L),
          stub.codeFor("+4915112345678").contains("111222"),
          stub.codeFor("+4900000000000").isEmpty,
        )
      },
      test("a number longer than the code in the text is not taken for it") {
        val stub = SmsStub(codeLength = 6, rangeLength = 6, responseDelay = Duration.Zero)
        stub.accept(phone = "+4915112345678", message = "Call 12345678 for help")
        assertTrue(stub.codeFor("+4915112345678").isEmpty)
      },
    ),
    suite("Comparison")(
      test("a protected call may add up to the ceiling, an untouched one only noise") {
        val baseline =
          AntifraudReport(
            label = "off",
            startedAt = "t",
            scenarios = List(result("normal", "otp-request" -> 20.0, "otp-verify" -> 30.0, "token" -> 15.0, "refresh" -> 10.0)),
          )
        val run = AntifraudReport(
          label = "on",
          startedAt = "t",
          scenarios = List(result("normal", "otp-request" -> 29.0, "otp-verify" -> 41.0, "token" -> 18.0, "refresh" -> 17.0)),
        )
        val comparisons = Comparison.compare(report = run, baseline = Some(baseline), ceilingMs = 10.0, toleranceMs = 5.0)
        assertTrue(
          verdictOf(comparisons, "otp-request") == Verdict.Pass,
          verdictOf(comparisons, "otp-verify") == Verdict.Fail,
          verdictOf(comparisons, "token") == Verdict.Pass,
          verdictOf(comparisons, "refresh") == Verdict.Fail,
        )
      },
      test("an untouched call that got faster is within the noise, not a failure") {
        val baseline = AntifraudReport(label = "off", startedAt = "t", scenarios = List(result("normal", "token" -> 15.0)))
        val run = AntifraudReport(label = "on", startedAt = "t", scenarios = List(result("normal", "token" -> 12.0)))
        assertTrue(verdictOf(
          Comparison.compare(report = run, baseline = Some(baseline), ceilingMs = 10.0, toleranceMs = 5.0),
          "token",
        ) == Verdict.Pass)
      },
      test("without a baseline there is nothing to pass or fail") {
        val run = AntifraudReport(label = "off", startedAt = "t", scenarios = List(result("normal", "otp-request" -> 20.0)))
        assertTrue(Comparison.compare(report = run, baseline = None, ceilingMs = 10.0, toleranceMs = 5.0).map(_.verdict) == List(Verdict.NoBaseline))
      },
    ),
    suite("Checks")(
      test("an attack that gets most of its codes sent fails, one that is stopped passes") {
        val open = result("device-attack").copy(
          otp = OtpOutcomes(requested = 100, sent = 100, refused = 0, other = 0, verified = 0, rejected = 0, failed = 0, notEntered = 0),
          smsSent = 99L,
        )
        val stopped = result("prefix-attack").copy(
          otp = OtpOutcomes(requested = 100, sent = 5, refused = 95, other = 0, verified = 0, rejected = 0, failed = 0, notEntered = 0),
          smsSent = 5L,
        )
        val checks = Checks.of(
          AntifraudReport(label = "on", startedAt = "t", scenarios = List(open, stopped)),
          attackSmsCeiling = 0.1,
          legitRefusalCeiling = 0.01,
        )
        assertTrue(checks.map(c => c.scenario -> c.verdict).toMap == Map("device-attack" -> Verdict.Fail, "prefix-attack" -> Verdict.Pass))
      },
      test("the distributed attack is judged on its codes sent like the other attacks") {
        val spread = result("distributed-attack").copy(
          otp = OtpOutcomes(requested = 100, sent = 50, refused = 50, other = 0, verified = 0, rejected = 0, failed = 0, notEntered = 0),
          smsSent = 50L,
        )
        val checks =
          Checks.of(AntifraudReport(label = "on", startedAt = "t", scenarios = List(spread)), attackSmsCeiling = 0.1, legitRefusalCeiling = 0.01)
        assertTrue(checks.map(c => (c.name, c.value, c.verdict)) == List(("attack: share of codes sent", 0.5, Verdict.Fail)))
      },
      test("legitimate users refused in the attacked range fail the false-positive check, those elsewhere pass") {
        val refused = OtpOutcomes(requested = 50, sent = 10, refused = 40, other = 0, verified = 8, rejected = 0, failed = 0, notEntered = 0)
        val served = OtpOutcomes(requested = 450, sent = 450, refused = 0, other = 0, verified = 380, rejected = 0, failed = 0, notEntered = 0)
        val mixed = result("mixed-attack").copy(legitInRange = Some(refused), legitElsewhere = Some(served))
        val checks =
          Checks.of(AntifraudReport(label = "on", startedAt = "t", scenarios = List(mixed)), attackSmsCeiling = 0.1, legitRefusalCeiling = 0.01)
        assertTrue(
          checks.find(_.name.contains("attacked range")).get.verdict == Verdict.Fail,
          checks.find(_.name.contains("elsewhere")).get.verdict == Verdict.Pass,
        )
      },
      test("a guess that is taken fails, none taken passes") {
        val taken = result("verify-guessing").copy(otp =
          OtpOutcomes(requested = 10, sent = 10, refused = 0, other = 0, verified = 1, rejected = 49, failed = 0, notEntered = 0),
        )
        val none = result("verify-guessing").copy(otp =
          OtpOutcomes(requested = 10, sent = 10, refused = 0, other = 0, verified = 0, rejected = 50, failed = 0, notEntered = 0),
        )
        def verdict(r: ScenarioResult) = Checks.of(
          AntifraudReport(label = "x", startedAt = "t", scenarios = List(r)),
          attackSmsCeiling = 0.1,
          legitRefusalCeiling = 0.01,
        ).head.verdict
        assertTrue(verdict(taken) == Verdict.Fail, verdict(none) == Verdict.Pass)
      },
    ),
    suite("Sources")(
      test("addresses are private IPv4, distinct within a run, and never a .0 or .255") {
        val sources = Sources(SplittableRandom(7))
        val pool = sources.pool(2000)
        val more = List.fill(100)(sources.next())
        val octets = (pool ++ more).map(_.split('.').map(_.toInt))
        assertTrue(
          pool.size == 2000,
          (pool ++ more).distinct.size == 2100,
          octets.forall(o => o.length == 4 && o.head == 10 && o.forall(x => x >= 0 && x <= 255)),
          octets.forall(o => o(3) >= 1 && o(3) <= 254),
        )
      },
    ),
    suite("Measurements")(
      test("quantiles are of the successful calls, and a failure is only counted") {
        val measurements = Measurements()
        (1 to 100).foreach(n => measurements.record("token", n * 1000L))
        measurements.failed("token")
        measurements.failed("refresh")
        val quantiles = measurements.quantiles
        assertTrue(
          quantiles("token").count == 100L,
          quantiles("token").errors == 1L,
          quantiles("token").p50Ms >= 49.0 && quantiles("token").p50Ms <= 51.0,
          quantiles("token").p99Ms >= 98.0 && quantiles("token").p99Ms <= 100.0,
          quantiles("refresh") == Quantiles.none.copy(errors = 1L),
        )
      },
    ),
    suite("AntifraudConfig")(
      test("decodes the block, and leaves the optional keys out as None") {
        val hocon =
          """label = off
            |client-id = mobile-otp
            |redirect-uri = "https://app.versola.test/callback"
            |scope = "openid offline_access"
            |auth-metrics-url = "http://localhost:9004/metrics"
            |report-path = "r.json"
            |otp { source = stub, length = 6 }
            |sms-stub { bind-host = "0.0.0.0", port = 9100, response-delay = 0s }
            |scenarios = ["normal", "prefix-attack"]
            |numbers { dial-code = "+49", national-length = 11, leads = ["151"], range-length = 6 }
            |normal { rate = 5.0, duration = 10s, conversion = 0.85, refreshes-min = 5, refreshes-max = 10 }
            |prefix-attack { rate = 1.0, duration = 10s }
            |device-attack { rate = 1.0, duration = 10s }
            |distributed-attack { rate = 1.0, duration = 10s }
            |token-control { sessions = 10, rate = 1.0, duration = 10s }
            |mixed { normal-rate = 5.0, attack-rate = 5.0, duration = 10s, legit-in-range-share = 0.1 }
            |mimicking { rate = 1.0, duration = 10s, verify-share = 0.3 }
            |targeted { rate = 1.0, duration = 10s }
            |guessing { rate = 1.0, duration = 10s, attempts = 5 }
            |attack-sms-ceiling = 0.1
            |legit-refusal-ceiling = 0.01
            |max-in-flight = 256
            |warmup-sessions = 1
            |queue-sample-every = 250ms
            |control-tolerance = 5ms
            |added-latency-ceiling = 10ms
            |sources { header = "", legit = 0, distributed = 0 }
            |""".stripMargin
        for config <- TypesafeConfigProvider.fromHoconString(hocon).kebabCase.load(deriveConfig[AntifraudConfig])
        yield assertTrue(
          config.selected == List(AntifraudScenario.Normal, AntifraudScenario.PrefixAttack),
          !config.sources.enabled,
          config.otp == OtpDeliveryConfig(source = OtpSource.Stub, length = 6),
          config.normal.rate == 5.0,
          config.normal.refreshesMin == 5 && config.normal.refreshesMax == 10,
          config.baselinePath.isEmpty,
          config.seed.isEmpty,
          config.addedLatencyCeiling == 10.millis,
        )
      },
      test("the antifraud block of loadgen/antifraud/loadgen.conf decodes whole, every key stated") {
        val file = List("loadgen/antifraud/loadgen.conf", "antifraud/loadgen.conf").map(Path.of(_)).find(Files.exists(_)).get
        for config <- TypesafeConfigProvider
            .fromHoconFile(file.toFile)
            .kebabCase
            .load(Config.Nested("antifraud", deriveConfig[AntifraudConfig]))
        yield assertTrue(
          config.selected.size == 9,
          config.addedLatencyCeiling == 10.millis,
          config.tokenControl.sessions == 1000,
          config.sources == SourcesConfig(header = "X-Forwarded-For", legit = 2000, distributed = 500),
        )
      },
      test("an empty list of scenarios means all of them, the controls first") {
        val config = AntifraudConfig(
          label = "off",
          clientId = "c",
          redirectUri = "r",
          scope = "s",
          authMetricsUrl = "u",
          reportPath = "p",
          baselinePath = None,
          scenarios = Nil,
          otp = OtpDeliveryConfig(source = OtpSource.Fixed, length = 6),
          smsStub = SmsStubConfig(bindHost = "0.0.0.0", port = 9100, responseDelay = Duration.Zero),
          numbers = plan,
          normal = NormalTrafficConfig(rate = 1.0, duration = 1.second, conversion = 1.0, refreshesMin = 1, refreshesMax = 1),
          prefixAttack = AttackConfig(rate = 1.0, duration = 1.second),
          deviceAttack = AttackConfig(rate = 1.0, duration = 1.second),
          distributedAttack = AttackConfig(rate = 1.0, duration = 1.second),
          tokenControl = TokenControlConfig(sessions = 1, rate = 1.0, duration = 1.second),
          mixed = MixedConfig(normalRate = 1.0, attackRate = 1.0, duration = 1.second, legitInRangeShare = 0.1),
          mimicking = MimickingConfig(rate = 1.0, duration = 1.second, verifyShare = 0.3),
          targeted = AttackConfig(rate = 1.0, duration = 1.second),
          guessing = GuessingConfig(rate = 1.0, duration = 1.second, attempts = 5),
          attackSmsCeiling = 0.1,
          legitRefusalCeiling = 0.01,
          maxInFlight = 1,
          warmupSessions = 0,
          queueSampleEvery = 250.millis,
          controlTolerance = 5.millis,
          addedLatencyCeiling = 10.millis,
          seed = None,
          sources = SourcesConfig(header = "X-Forwarded-For", legit = 10, distributed = 5),
        )
        assertTrue(
          config.selected == AntifraudScenario.values.toList.filterNot(_ == AntifraudScenario.FakeTiming),
          config.selected.drop(2).head == AntifraudScenario.PrefixAttack,
          // it needs a list entry that was made beforehand: only when asked for by name
          !config.selected.contains(AntifraudScenario.FakeTiming),
          config.copy(scenarios = List(AntifraudScenario.FakeTiming)).selected == List(AntifraudScenario.FakeTiming),
          config.metricsUrls == List("u"),
          config.copy(authMetricsUrls = List("a", "b")).metricsUrls == List("a", "b"),
          config.otp.client == OtpClient.Script,
        )
      },
      test("the keys of the challenge client, the plain client, the fake timing and the instances decode, and default when left out") {
        val file = List("loadgen/antifraud/loadgen.conf", "antifraud/loadgen.conf").map(Path.of(_)).find(Files.exists(_)).get
        val extra =
          """antifraud {
            |  auth-metrics-urls = ["http://localhost:9104/metrics", "http://localhost:9114/metrics"]
            |  otp.client = plain
            |  challenge { max-rounds = 2, max-difficulty = 20, pause = 5s, pause-slack = 500ms, max-asks = 4, solver-threads = 2 }
            |  fake-timing { requests = 50, rate = 10.0, tolerance = 30ms, warmup = 5, fake-prefix = "491599" }
            |}
            |""".stripMargin
        val tmp = Files.createTempFile("af-extra", ".conf")
        Files.writeString(tmp, s"include file(\"${file.toAbsolutePath}\")\n$extra")
        for
          config <- TypesafeConfigProvider.fromHoconFile(tmp.toFile).kebabCase.load(Config.Nested("antifraud", deriveConfig[AntifraudConfig]))
          shipped <- TypesafeConfigProvider.fromHoconFile(file.toFile).kebabCase.load(Config.Nested("antifraud", deriveConfig[AntifraudConfig]))
        yield assertTrue(
          config.metricsUrls.size == 2,
          config.otp.client == OtpClient.Plain,
          config.challenge == ChallengeClientConfig(maxRounds = 2, maxDifficulty = 20, pause = 5.seconds, pauseSlack = 500.millis, maxAsks = 4, solverThreads = 2),
          config.fakeTiming == FakeTimingConfig(requests = 50, rate = 10.0, tolerance = 30.millis, warmup = 5, fakePrefix = Some("491599")),
          shipped.otp.client == OtpClient.Script,
          shipped.challenge == ChallengeClientConfig(),
          shipped.fakeTiming.tolerance == 25.millis,
        )
      },
    ),
    suite("TargetsConfig")(
      test("auth-url alone is one instance, auth-urls are all of them") {
        val one = versola.loadgen.config.TargetsConfig("http://a", "e", "m", "o")
        assertTrue(
          one.allAuthUrls == List("http://a"),
          one.copy(authUrls = List("http://a", "http://b")).allAuthUrls == List("http://a", "http://b"),
        )
      },
      test("the targets decode with and without auth-urls") {
        val base = """auth-url = "http://a", edge-url = "e", mock-url = "m", origin = "o""""
        for
          one <- TypesafeConfigProvider.fromHoconString(base).kebabCase.load(deriveConfig[versola.loadgen.config.TargetsConfig])
          two <- TypesafeConfigProvider.fromHoconString(base + """, auth-urls = ["http://a", "http://b"]""").kebabCase.load(deriveConfig[versola.loadgen.config.TargetsConfig])
        yield assertTrue(one.allAuthUrls == List("http://a"), two.allAuthUrls == List("http://a", "http://b"))
      },
    ),
    suite("reports")(
      test("a report written before the challenge fields existed still decodes, with those fields at zero or none") {
        val old =
          """{"label":"off","startedAt":"2026-10-29T10:00:00Z","scenarios":[{"scenario":"normal","seconds":1.0,"arrivals":1,"waited":0,
            |"latencies":{"otp-request":{"count":1,"errors":0,"p50Ms":1.0,"p99Ms":2.0,"maxMs":2.0}},
            |"otp":{"requested":1,"sent":1,"refused":0,"other":0,"verified":1,"rejected":0,"failed":0,"notEntered":0},
            |"failures":{},"smsSent":1,"smsRanges":1,"smsBusiestRange":1,
            |"auth":{"enqueued":1.0,"written":1.0,"dropped":{},"smsRefused":0.0,"smsBudgetFull":0.0,"queueSize":0.0}}]}""".stripMargin
        val decoded = old.fromJson[AntifraudReport]
        assertTrue(
          decoded.isRight,
          decoded.exists(_.scenarios.head.otp.challenged == 0L),
          decoded.exists(_.scenarios.head.challenge.isEmpty),
          decoded.exists(_.client == "script"),
        )
      },
      test("a report with the new fields round-trips") {
        val timing = FakeTimingCheck.judge(
          fake = TimingGroup(25, 25, 0, 0, 0, 80.0, 90.0, 95.0, 96.0),
          real = TimingGroup(25, 25, 0, 0, 25, 81.0, 92.0, 99.0, 100.0),
          toleranceMs = 25.0,
          minSamples = 20,
        )
        val scenario = result("fake-timing").copy(fakeTiming = Some(timing), challenge = Some(solvedStats))
        val report = AntifraudReport("challenge", "now", List(scenario), client = "plain", instances = List("http://a", "http://b"))
        assertTrue(report.toJson.fromJson[AntifraudReport] == Right(report))
      },
      test("the text names the client and the instances, and keeps challenged, solved and refused apart") {
        val o = OtpOutcomes(requested = 10, sent = 8, refused = 1, other = 1, verified = 0, rejected = 0, failed = 0, notEntered = 0, challenged = 6, solved = 5, paidByWaiting = 0)
        val scenario = result("normal", "otp-request" -> 5.0).copy(otp = o)
        val report = AntifraudReport("challenge", "now", List(scenario), client = "script", instances = List("http://a", "http://b"))
        val text = ReportText.render(report, Comparison.compare(report, None, 10.0, 5.0), Checks.of(report, 0.1, 0.01), 10.0, 5.0)
        assertTrue(
          text.contains("Client: `script`; auth instances: http://a, http://b."),
          text.contains("| challenged | solved | paid by waiting |"),
          text.contains("| 10 | 8 | 1 | 6 | 5 | 0 | 1 |"),
        )
      },
    ),
    suite("FakeTimingCheck")(
        test("a fake that answers as a real one does, and is never sent, passes") {
          val result = FakeTimingCheck.judge(group(82.0, 0, 30), group(80.0, 30, 30), 25.0, 20)
          assertTrue(result.passed, result.problems.isEmpty, result.medianGapMs == 2.0, result.p99GapMs == 2.0)
        },
        test("a fake that is 60 ms faster than a real send fails on the medians") {
          val result = FakeTimingCheck.judge(group(20.0, 0, 30), group(80.0, 30, 30), 25.0, 20)
          assertTrue(!result.passed, result.medianGapMs == -60.0, result.problems.exists(_.contains("medians differ")))
        },
        test("a fake number that was sent a code means the entry did not apply, whatever the timing says") {
          val result = FakeTimingCheck.judge(group(80.0, 12, 30), group(80.0, 30, 30), 25.0, 20)
          assertTrue(!result.passed, result.problems.exists(_.contains("12 of the fake numbers were sent a code")))
        },
        test("no real number reaching the stub, or too few requests, fail it: nothing was compared") {
          val unwired = FakeTimingCheck.judge(group(80.0, 0, 30), group(80.0, 0, 30), 25.0, 20)
          val few = FakeTimingCheck.judge(group(80.0, 0, 5), group(80.0, 5, 30), 25.0, 20)
          assertTrue(
            !unwired.passed, unwired.setupProblems.size == 1,
            !few.passed, few.setupProblems.exists(_.contains("only 5 fake")),
          )
        },
        test("the checks of a report carry the gap and the fake numbers sent") {
          val timing = FakeTimingCheck.judge(group(20.0, 3, 30), group(80.0, 30, 30), 25.0, 20)
          val report = AntifraudReport("x", "now", List(result("fake-timing").copy(fakeTiming = Some(timing))))
          val checks = Checks.of(report, 0.1, 0.01).filter(_.scenario == "fake-timing")
          assertTrue(
            checks.size == 3,
            checks.find(_.name.startsWith("fake vs real")).exists(c => c.value == 60.0 && c.verdict == Verdict.Fail),
            checks.find(_.name == "fake numbers sent a code").exists(c => c.value == 3.0 && c.verdict == Verdict.Fail),
          )
        },
    ),
    suite("metrics of several instances")(
      test("the counters and the queues of the instances are added") {
        val a = AuthAntifraudMetrics.parse(exposition)
        val b = AuthAntifraudMetrics.parse(exposition.replace("120.0", "30.0").replace("queue_size 17.0", "queue_size 3.0"))
        val sum = a.plus(b)
        assertTrue(sum.enqueued == 150.0, sum.written == 200.0, sum.dropped("queue_full") == 6.0, sum.queueSize == 20.0, sum.smsRefused == 80.0)
      },
      test("a run's delta over instances is the delta of the sums") {
        val before = AuthAntifraudMetrics.parse(exposition).plus(AuthAntifraudMetrics.parse(exposition))
        val after = before.plus(AuthAntifraudMetrics.parse(exposition))
        assertTrue(after.minus(before).enqueued == 120.0)
      },
    ),
    suite("Comparison of the resend")(
      test("the ask of a plain client for its code is a protected call, held to the ceiling and not the noise") {
        val base = AntifraudReport("off", "t", List(result("normal", "otp-resend" -> 20.0)))
        val run = AntifraudReport("x", "t", List(result("normal", "otp-resend" -> 28.0)))
        val comparisons = Comparison.compare(run, Some(base), ceilingMs = 10.0, toleranceMs = 5.0)
        assertTrue(verdictOf(comparisons, "otp-resend") == Verdict.Pass)
      },
    ),
  )

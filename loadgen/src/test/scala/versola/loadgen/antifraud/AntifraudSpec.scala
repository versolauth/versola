package versola.loadgen.antifraud

import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*
import zio.{Duration, durationInt}

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
      failures = Map.empty,
      smsSent = 0,
      smsRanges = 0,
      smsBusiestRange = 0,
      auth = AuthAntifraudMetrics.empty,
    )

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
      test("decodes the block with the defaults for what it leaves out") {
        val hocon =
          """label = off
            |client-id = mobile-otp
            |redirect-uri = "https://app.versola.test/callback"
            |scope = "openid offline_access"
            |auth-metrics-url = "http://localhost:9004/metrics"
            |report-path = "r.json"
            |otp { source = stub }
            |sms-stub { port = 9100 }
            |scenarios = ["normal", "prefix-attack"]
            |normal { rate = 5.0 }
            |""".stripMargin
        for config <- TypesafeConfigProvider.fromHoconString(hocon).kebabCase.load(deriveConfig[AntifraudConfig])
        yield assertTrue(
          config.selected == List(AntifraudScenario.Normal, AntifraudScenario.PrefixAttack),
          config.otp == OtpDeliveryConfig(OtpSource.Stub, 6),
          config.normal.rate == 5.0,
          config.normal.refreshesMin == 5 && config.normal.refreshesMax == 10,
          config.baselinePath.isEmpty,
          config.addedP99Ceiling == 10.millis,
        )
      },
      test("an empty list of scenarios means all of them, the attacks last") {
        val config = AntifraudConfig(
          label = "off",
          clientId = "c",
          redirectUri = "r",
          scope = "s",
          authMetricsUrl = "u",
          reportPath = "p",
          otp = OtpDeliveryConfig(OtpSource.Fixed),
          smsStub = SmsStubConfig(),
        )
        assertTrue(
          config.selected == AntifraudScenario.values.toList,
          config.selected.takeRight(2) == List(AntifraudScenario.PrefixAttack, AntifraudScenario.DeviceAttack),
        )
      },
    ),
  )

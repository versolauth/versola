package versola.loadgen.metrics

import versola.loadgen.store.SutStatPhase
import versola.loadgen.sut.{SutStatsDelta, SutStatsFixture}
import zio.json.*
import zio.test.*
import zio.{Chunk, Duration}

import java.time.Instant

object CampaignReportSpec extends ZIOSpecDefault:

  private val tokenRefresh = MeasurementId.Step("mobile-otp", "token-refresh")
  private val edgeProxy = MeasurementId.Step("mobile-otp", "proxy-accounts")
  private val mockBackend = MeasurementId.Step("mockapi", "accounts")

  private val thresholds =
    AcceptanceThresholds.designDefaults(List(LatencyThreshold(tokenRefresh, Duration.fromMillis(120))), edgeProxy, mockBackend)

  private def sample(id: MeasurementId, micros: Long, count: Long): HistogramSample =
    val histogram = LatencyRecorder.emptyHistogram
    histogram.recordValueWithCount(micros, count)
    HistogramSample(id, histogram)

  private def driverReport(campaign: String, driverId: String, samples: HistogramSample*): DriverHistogramReport =
    HistogramWire.report(campaign, driverId, Instant.parse("2026-09-10T18:00:00Z"), Chunk.fromIterable(samples))

  private val healthyRun = CampaignHealth(
    refreshRejectedTotal = 0L,
    flushDroppedTotal = 0L,
    maxDriverCpu = Some(0.29),
    scheduleLagP99Micros = Some(180_000L),
    latencyClampedTotal = 0L,
  )

  private val startedAt = Instant.parse("2026-09-10T18:00:00Z")

  private val campaignRun = CampaignRun(
    phases = List(RunPhase("steady", 36_000_000L, Some(1.0), None, None)),
    population = Map("registered" -> 10_000_000L),
    shardCount = 8,
    shardEpoch = 4L,
    tokenMode = TokenMode.Bearer,
    observedTokenTypes = List("Bearer"),
    accessTokenTtls = List(ObservedAccessTokenTtl("mobile-otp", List(900L))),
  )

  private val allMeasured = List(
    driverReport(
      "c3-10m-steady",
      "driver-0",
      sample(tokenRefresh, 90_000L, 100L),
      sample(edgeProxy, 40_000L, 100L),
      sample(mockBackend, 30_000L, 100L),
    ),
  )

  def spec = suite("CampaignReport")(
    test("the design doc's thresholds are the ones being applied") {
      assertTrue(
        thresholds.latency == List(LatencyThreshold(tokenRefresh, Duration.fromMillis(120))),
        thresholds.relativeLatency == List(RelativeLatencyThreshold(edgeProxy, mockBackend, Duration.fromMillis(15))),
        thresholds.scheduleLagP99 == Duration.fromMillis(250),
        thresholds.maxRefreshRejected == 0L,
        thresholds.maxFlushDropped == 0L,
        thresholds.maxDriverCpu == 0.4,
        thresholds.maxLatencyClamped == 0L,
      )
    },
    test("passes a campaign that meets every criterion") {
      val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(true),
        report.map(_.notEvaluated) == Right(Nil),
        report.map(_.checks.size) == Right(9),
        report.map(_.drivers) == Right(List("driver-0")),
        report.map(_.latency.map(_.count)) == Right(List(100L, 100L, 100L)),
      )
    },
    test("the window is the oldest and newest snapshot actually merged, not this process's clock") {
      val reports = List(
        HistogramWire.report("c3", "driver-0", Instant.parse("2026-09-10T18:00:00Z"), Chunk(sample(tokenRefresh, 90_000L, 10L))),
        HistogramWire.report("c3", "driver-1", Instant.parse("2026-09-10T18:05:00Z"), Chunk(sample(tokenRefresh, 90_000L, 10L))),
      )
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.startEpochMillis) == Right(Instant.parse("2026-09-10T18:00:00Z").toEpochMilli),
        report.map(_.endEpochMillis) == Right(Instant.parse("2026-09-10T18:05:00Z").toEpochMilli),
      )
    },
    test("refuses to build a window with no driver reports to take it from") {
      assertTrue(CampaignReport.assemble("c3", Nil, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None).isLeft)
    },
    test("fails on the token endpoint's absolute p99") {
      val reports = List(
        driverReport(
          "c3",
          "driver-0",
          sample(tokenRefresh, 140_000L, 10L),
          sample(edgeProxy, 40_000L, 10L),
          sample(mockBackend, 30_000L, 10L),
        ),
      )
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.checks.filterNot(_.passed).map(_.name)) == Right(List(s"p99 of $tokenRefresh")),
      )
    },
    test("fails when edge's p99 exceeds the backend's by more than the margin") {
      val reports = List(
        driverReport(
          "c3",
          "driver-0",
          sample(tokenRefresh, 90_000L, 10L),
          sample(edgeProxy, 60_000L, 10L),
          sample(mockBackend, 30_000L, 10L),
        ),
      )
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.checks.count(_.passed == false)) == Right(1),
      )
    },
    test("a step-up-heavy campaign with no failures still passes the error budget") {
      val taxonomy = ErrorTaxonomy.empty
        .recordMany(StepOutcome.ok, 100_000L)
        .recordMany(StepOutcome.Planned(PlannedOutcome.StepUp), 30_000L)
        .recordMany(StepOutcome.Planned(PlannedOutcome.Forbidden), 1_000L)
        .recordMany(StepOutcome.Planned(PlannedOutcome.Unauthorized), 4_000L)
      val report = CampaignReport.assemble("c3-10m-steady", allMeasured, taxonomy, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(true),
        report.map(_.taxonomy.budgetConsumed) == Right(0L),
      )
    },
    test("a single failure exhausts a zero error budget and fails the campaign") {
      // designDefaults sets maxErrorBudgetRatio = 0.0, so this is the one check every other test
      // in this file leaves unexercised by keeping budgetConsumed at 0 -- without this test a
      // regression that stopped comparing budgetRatio against the threshold at all would still
      // pass the whole suite.
      val taxonomy = ErrorTaxonomy.empty
        .recordMany(StepOutcome.ok, 99_999L)
        .record(StepOutcome.Failed(FailedOutcome.Transport))
      val report = CampaignReport.assemble("c3-10m-steady", allMeasured, taxonomy, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.checks.filterNot(_.passed).map(_.name)) == Right(List("error budget")),
      )
    },
    test("fails on driver health rather than blaming the SUT") {
      val unhealthy = CampaignHealth(
        refreshRejectedTotal = 3L,
        flushDroppedTotal = 41L,
        maxDriverCpu = Some(0.62),
        scheduleLagP99Micros = Some(900_000L),
        latencyClampedTotal = 0L,
      )
      val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, unhealthy, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.checks.filterNot(_.passed).map(_.name).sorted) == Right(
          List("driver CPU", "dropped write-behind rows", "refresh rejections", "schedule lag p99"),
        ),
      )
    },
    test("an unmeasured threshold is reported as not evaluated, not as a pass") {
      val reports = List(driverReport("c3", "driver-0", sample(tokenRefresh, 90_000L, 10L)))
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.notEvaluated) == Right(List(s"p99 of $edgeProxy relative to $mockBackend")),
        report.map(_.checks.forall(_.passed)) == Right(true),
      )
    },
    test("a single clamped latency fails the campaign") {
      // A clamped sample means the reported tail is a floor rather than a measurement, so the
      // report's headline number is no longer the thing it claims to be. With the driver timing
      // out at 30 s against a 60 s recorder ceiling it also cannot happen without a bug in the
      // driver -- so one is the limit, and everything else about the run being healthy is exactly
      // when this needs to still fail.
      val clamped = healthyRun.copy(latencyClampedTotal = 1L)
      val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, clamped, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.checks.filterNot(_.passed).map(_.name)) == Right(List("clamped latencies")),
      )
    },
    test("a present but empty histogram is not evaluated, so a zero-sample campaign cannot pass") {
      // A recorder registered and never written to: HdrHistogram answers p99 with 0, which clears
      // every ceiling. Absence and emptiness have to reach the same branch or the report's worst
      // failure mode is available -- a campaign that measured nothing reporting success.
      val empty = HistogramSample(tokenRefresh, LatencyRecorder.emptyHistogram)
      val reports = List(
        driverReport(
          "c3",
          "driver-0",
          empty,
          HistogramSample(edgeProxy, LatencyRecorder.emptyHistogram),
          HistogramSample(mockBackend, LatencyRecorder.emptyHistogram),
        ),
      )
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.notEvaluated.sorted) == Right(
          List(s"p99 of $edgeProxy relative to $mockBackend", s"p99 of $tokenRefresh").sorted,
        ),
      )
    },
    test("an empty baseline does not silently become a zero-microsecond relative ceiling") {
      // The subject has real samples; only the baseline is empty. Evaluating it would compare a
      // measured p99 against `0 + margin` and fail the campaign for the wrong reason.
      val reports = List(
        driverReport(
          "c3",
          "driver-0",
          sample(tokenRefresh, 90_000L, 10L),
          sample(edgeProxy, 40_000L, 10L),
          HistogramSample(mockBackend, LatencyRecorder.emptyHistogram),
        ),
      )
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.notEvaluated) == Right(List(s"p99 of $edgeProxy relative to $mockBackend")),
        report.map(_.checks.forall(_.passed)) == Right(true),
        report.map(_.passed) == Right(false),
      )
    },
    test("merges every driver's histograms into one set of quantiles") {
      val reports = List(
        driverReport("c3", "driver-1", sample(tokenRefresh, 1000L, 100L)),
        driverReport("c3", "driver-0", sample(tokenRefresh, 2000L, 100L)),
      )
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
      assertTrue(
        report.map(_.drivers) == Right(List("driver-0", "driver-1")),
        report.map(_.latency.map(_.count)) == Right(List(200L)),
        report.map(_.latency.map(_.p50Micros)) == Right(List(1000L)),
        report.map(_.latency.map(_.p99Micros)) == Right(List(2000L)),
        report.map(_.latency.map(_.meanMicros)) == Right(List(1500.0)),
      )
    },
    test("refuses a driver's report from a different campaign") {
      val reports = List(
        driverReport("c3", "driver-0", sample(tokenRefresh, 1000L, 1L)),
        driverReport("c7-20m", "driver-1", sample(tokenRefresh, 1000L, 1L)),
      )
      assertTrue(
        CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None).isLeft,
      )
    },
    test("serialises to JSON for GET /report/{campaign}") {
      val report =
        CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None).toOption.get
      val json = report.toJson
      assertTrue(
        json.fromJson[CampaignReport].map(_.passed) == Right(true),
        json.fromJson[CampaignReport].map(_.latency.size) == Right(3),
        json.fromJson[CampaignReport].map(_.health) == Right(healthyRun),
        json.fromJson[CampaignReport].map(_.startEpochMillis) == Right(report.startEpochMillis),
        json.fromJson[CampaignReport].map(_.endEpochMillis) == Right(report.endEpochMillis),
        json.fromJson[CampaignReport].map(_.run) == Right(campaignRun),
      )
    },
    // §3 of the report is evidence, not a verdict: a WAL rate has no pass mark, so the section
    // must not touch `passed` in either direction.
    test("carries the SUT's database deltas through the merge and the JSON without judging them") {
      val deltas = List(
        SutStatsDelta.between(
          SutStatsFixture.row("c3-10m-steady", "auth", SutStatPhase.Before, startedAt, None, SutStatsFixture.stats(1L, 100L)),
          SutStatsFixture.row(
            "c3-10m-steady",
            "auth",
            SutStatPhase.After,
            startedAt.plusSeconds(3_600L),
            None,
            SutStatsFixture.stats(4L, 900L),
          ),
        ),
      )
      val report =
        CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, Some(deltas), None, None)
      val json = report.toOption.get.toJson
      assertTrue(
        report.map(_.passed) == Right(true),
        report.map(_.checks.size) == Right(9),
        json.fromJson[CampaignReport].map(_.databases.map(_.map(_.database))) == Right(Some(List("auth"))),
        json.fromJson[CampaignReport].map(_.databases.flatMap(_.head.counters).map(_.wal.map(_.bytes))) ==
          Right(Some(Some(3L * 1024L))),
      )
    },
    // The reason the field is microseconds and not a `zio.Duration`: a consumer comparing the
    // measured lag against the ceiling printed beside it should not first have to parse
    // "PT0.18S". Asserting on the rendered JSON rather than on the round-trip, because a
    // round-trip succeeds either way.
    test("states schedule lag as a number a dashboard can plot, not an ISO-8601 string") {
      val json = CampaignReport
        .assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
        .toOption
        .get
        .toJson
      assertTrue(json.contains("\"scheduleLagP99Micros\":180000"), !json.contains("PT"))
    },
    // §6 grades a table of endpoints, not one. Before the acceptance list existed, every
    // endpoint other than `/token` and the edge/backend pair had an empty assessment column no
    // matter what the campaign measured.
    suite("per-endpoint ceilings")(
      test("grades every measurement the campaign named, each against its own ceiling") {
        val perEndpoint = AcceptanceThresholds.designDefaults(
          List(
            LatencyThreshold(tokenRefresh, Duration.fromMillis(120)),
            LatencyThreshold(edgeProxy, Duration.fromMillis(200)),
          ),
          edgeProxy,
          mockBackend,
        )
        val report =
          CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, campaignRun, perEndpoint, None, None, None)
        assertTrue(
          report.map(_.checks.count(_.name.startsWith("p99 of"))) == Right(3),
          report.map(_.passed) == Right(true),
        )
      },
      // The point of per-endpoint ceilings: one slow endpoint fails the campaign even though it
      // is well inside the ceiling that used to be applied to everything.
      test("fails the campaign for an endpoint that passed the old single ceiling") {
        val strict = AcceptanceThresholds.designDefaults(
          List(LatencyThreshold(tokenRefresh, Duration.fromMillis(1))),
          edgeProxy,
          mockBackend,
        )
        val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, campaignRun, strict, None, None, None)
        assertTrue(
          report.map(_.passed) == Right(false),
          report.map(_.checks.filter(_.name == s"p99 of $tokenRefresh").map(_.passed)) == Right(List(false)),
        )
      },
    ),
    suite("the run's header")(
      test("carries the plan, the population, the shard map in force and what the SUT answered") {
        val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
        assertTrue(
          report.map(_.run.phases.map(_.name)) == Right(List("steady")),
          report.map(_.run.population) == Right(Map("registered" -> 10_000_000L)),
          report.map(_.run.shardCount) == Right(8),
          report.map(_.run.shardEpoch) == Right(4L),
          report.map(_.run.accessTokenTtls) == Right(List(ObservedAccessTokenTtl("mobile-otp", List(900L)))),
        )
      },
      test("passes the token-mode check when every token came back as the mode the run drove") {
        val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, campaignRun, thresholds, None, None, None)
        assertTrue(report.map(_.checks.filter(_.name == "token mode").map(_.passed)) == Right(List(true)))
      },
      // The divergence the header exists to surface: the driver presented bearer tokens and the
      // SUT issued sender-constrained ones, so the campaign measured a flow nobody asked for.
      test("fails the campaign when the SUT answered in a mode the run did not drive") {
        val diverged = campaignRun.copy(observedTokenTypes = List("Bearer", "DPoP"))
        val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, diverged, thresholds, None, None, None)
        assertTrue(
          report.map(_.passed) == Right(false),
          report.map(_.checks.filter(_.name == "token mode").map(_.detail)) ==
            Right(List("DPoP against a run driven as bearer")),
        )
      },
      // A campaign that observed no type at all has not passed this criterion -- it was not
      // tested -- which is the same distinction `notEvaluated` draws for an unmeasured threshold.
      test("leaves the token mode unevaluated rather than passing it when nothing was observed") {
        val silent = campaignRun.copy(observedTokenTypes = Nil)
        val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, silent, thresholds, None, None, None)
        assertTrue(
          report.map(_.notEvaluated) == Right(List("token mode")),
          report.map(_.passed) == Right(false),
          report.map(_.checks.exists(_.name == "token mode")) == Right(false),
        )
      },
      test("a lowercase bearer is the same mode, since RFC 6749 §5.1 makes the value case-insensitive") {
        val lowercase = campaignRun.copy(observedTokenTypes = List("bearer"))
        val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, lowercase, thresholds, None, None, None)
        assertTrue(report.map(_.passed) == Right(true))
      },
    ),
  )

package versola.loadgen.metrics

import zio.json.*
import zio.test.*
import zio.{Chunk, Duration}

import java.time.Instant

object CampaignReportSpec extends ZIOSpecDefault:

  private val tokenRefresh = MeasurementId.Step("mobile-otp", "token-refresh")
  private val edgeProxy = MeasurementId.Step("mobile-otp", "proxy-accounts")
  private val mockBackend = MeasurementId.Step("mockapi", "accounts")

  private val thresholds = AcceptanceThresholds.designDefaults(tokenRefresh, edgeProxy, mockBackend)

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
    scheduleLagP99 = Some(Duration.fromMillis(180)),
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
      )
    },
    test("passes a campaign that meets every criterion") {
      val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, thresholds)
      assertTrue(
        report.map(_.passed) == Right(true),
        report.map(_.notEvaluated) == Right(Nil),
        report.map(_.checks.size) == Right(7),
        report.map(_.drivers) == Right(List("driver-0")),
        report.map(_.latency.map(_.count)) == Right(List(100L, 100L, 100L)),
      )
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
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, thresholds)
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
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, thresholds)
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
      val report = CampaignReport.assemble("c3-10m-steady", allMeasured, taxonomy, healthyRun, thresholds)
      assertTrue(
        report.map(_.passed) == Right(true),
        report.map(_.taxonomy.budgetConsumed) == Right(0L),
      )
    },
    test("fails on driver health rather than blaming the SUT") {
      val unhealthy = CampaignHealth(
        refreshRejectedTotal = 3L,
        flushDroppedTotal = 41L,
        maxDriverCpu = Some(0.62),
        scheduleLagP99 = Some(Duration.fromMillis(900)),
      )
      val report = CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, unhealthy, thresholds)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.checks.filterNot(_.passed).map(_.name).sorted) == Right(
          List("driver CPU", "dropped write-behind rows", "refresh rejections", "schedule lag p99"),
        ),
      )
    },
    test("an unmeasured threshold is reported as not evaluated, not as a pass") {
      val reports = List(driverReport("c3", "driver-0", sample(tokenRefresh, 90_000L, 10L)))
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, thresholds)
      assertTrue(
        report.map(_.passed) == Right(false),
        report.map(_.notEvaluated) == Right(List(s"p99 of $edgeProxy relative to $mockBackend")),
        report.map(_.checks.forall(_.passed)) == Right(true),
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
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, thresholds)
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
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, thresholds)
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
      val report = CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, thresholds)
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
        CampaignReport.assemble("c3", reports, ErrorTaxonomy.empty, healthyRun, thresholds).isLeft,
      )
    },
    test("serialises to JSON for GET /report/{campaign}") {
      val report =
        CampaignReport.assemble("c3-10m-steady", allMeasured, ErrorTaxonomy.empty, healthyRun, thresholds).toOption.get
      val json = report.toJson
      assertTrue(
        json.fromJson[CampaignReport].map(_.passed) == Right(true),
        json.fromJson[CampaignReport].map(_.latency.size) == Right(3),
        json.fromJson[CampaignReport].map(_.health) == Right(healthyRun),
      )
    },
  )

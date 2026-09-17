package versola.loadgen.coordinator

import versola.loadgen.config.LoadgenConfig
import versola.loadgen.metrics.{ErrorTaxonomy, MeasurementId, StepOutcome}
import versola.loadgen.model.VirtualUserState
import versola.loadgen.store.SutStatPhase
import versola.loadgen.sut.{PoolerStatsCapture, SutStatsCapture}
import zio.*
import zio.test.*

import java.time.Instant

/** The plan service end to end, on a test clock and in-memory collaborators: what `/plan`
  * publishes in each lifecycle state, when the drain commits, and what the registration
  * controller does to λ.
  */
object CoordinatorServiceSpec extends ZIOSpecDefault:

  private val campaign = "c3-10m-steady"

  private val t0 = Instant.parse("2026-09-15T08:00:00Z")

  private val tokenRefresh = MeasurementId.Step("mobile-otp", "token-refresh")

  private val taxonomy = ErrorTaxonomy.empty.recordMany(StepOutcome.ok, 500L)

  private final case class Harness(
      service: CoordinatorService,
      users: FakeVirtualUsers,
      snapshots: FakeMetricSnapshots,
      rebalancer: FakeRebalancer,
  )

  private def harness(
      config: ZIO[Any, zio.Config.Error, LoadgenConfig],
      users: FakeVirtualUsers,
      snapshots: FakeMetricSnapshots,
  ) =
    harnessWith(config, users, snapshots, None, None)

  private def harnessWith(
      config: ZIO[Any, zio.Config.Error, LoadgenConfig],
      users: FakeVirtualUsers,
      snapshots: FakeMetricSnapshots,
      sutStats: Option[SutStatsCapture],
      poolerStats: Option[PoolerStatsCapture],
  ) =
    for
      loaded <- config
      rebalancer <- FakeRebalancer.make
      service <- CoordinatorService
        .make(loaded, users, snapshots, rebalancer, sutStats, poolerStats)
        .mapError(RuntimeException(_))
      _ <- TestClock.setTime(t0)
    yield Harness(service, users, snapshots, rebalancer)

  private def steady =
    for
      users <- FakeVirtualUsers.make()
      snapshots <- FakeMetricSnapshots.make()
      harness <- harness(CoordinatorFixture.coordinatorConfig, users, snapshots)
    yield harness

  private def rate(plan: LoadPlan, scenario: PlanScenario): Option[ScenarioRate] =
    plan.scenarios.find(_.scenario == scenario)

  def spec = suite("CoordinatorService")(
    suite("plan")(
      test("an idle campaign publishes the map and no load at all") {
        for
          harness <- steady
          plan <- harness.service.plan
        yield assertTrue(
          plan.campaign == campaign,
          plan.state == CampaignState.Idle,
          plan.phase.isEmpty,
          plan.startedAtEpochMillis.isEmpty,
          plan.shards == ShardMap(epoch = 0L, shardCount = 8),
          plan.pollIntervalMillis == 10_000L,
          plan.scenarios.map(_.scenario) == List(PlanScenario.MobileSession, PlanScenario.WebSession),
          plan.scenarios.forall(scenario => scenario.basePerSecond == 0.0 && scenario.ratePerSecond == 0.0),
        )
      },
      test("a started campaign publishes the phase, the base rate and the rate under the envelope") {
        for
          harness <- steady
          _ <- harness.service.start
          plan <- harness.service.plan
        yield
          val mobile = rate(plan, PlanScenario.MobileSession).get
          val web = rate(plan, PlanScenario.WebSession).get
          assertTrue(
            plan.state == CampaignState.Running,
            plan.phase == Some("warmup"),
            plan.startedAtEpochMillis == Some(t0.toEpochMilli),
            math.abs(mobile.basePerSecond - 94.82) < 0.01,
            math.abs(web.basePerSecond - 12.93) < 0.01,
            // warmup runs at scale 0.1, and the diurnal envelope is on, so the published rate is
            // a tenth of the base times a factor that is not 1 -- but never above the ceiling.
            mobile.ratePerSecond > 0.0,
            mobile.ratePerSecond < mobile.basePerSecond,
            mobile.ratePerSecond > web.ratePerSecond,
          )
      },
      test("a paused campaign publishes zero, and resuming it keeps the phase it left") {
        for
          harness <- steady
          _ <- harness.service.start
          _ <- TestClock.adjust(10.minutes)
          _ <- harness.service.pause
          paused <- harness.service.plan
          _ <- TestClock.adjust(2.hours)
          _ <- harness.service.start
          resumed <- harness.service.plan
        yield assertTrue(
          paused.state == CampaignState.Paused,
          paused.phase.isEmpty,
          paused.scenarios.forall(_.ratePerSecond == 0.0),
          resumed.state == CampaignState.Running,
          // Ten minutes of campaign ran before the pause, so it is still in the 15-minute warmup
          // despite two hours of wall clock having passed.
          resumed.phase == Some("warmup"),
          resumed.startedAtEpochMillis == Some(t0.plusSeconds(7_200).toEpochMilli),
        )
      },
      test("a stopped campaign refuses to restart and keeps publishing nothing") {
        for
          harness <- steady
          _ <- harness.service.start
          _ <- harness.service.stop
          refused <- harness.service.start.either
          plan <- harness.service.plan
        yield assertTrue(
          refused.left.toOption.collect { case refusal: CoordinatorRefusal => refusal }.exists(_.reason.nonEmpty),
          plan.state == CampaignState.Stopped,
          plan.scenarios.forall(_.basePerSecond == 0.0),
        )
      },
      test("the campaign's phases run out into a plan that generates nothing") {
        for
          harness <- steady
          _ <- harness.service.start
          // warmup 15m + ramp 30m + steady 10h.
          _ <- TestClock.adjust(11.hours)
          plan <- harness.service.plan
        yield assertTrue(plan.phase.isEmpty, plan.scenarios.forall(_.ratePerSecond == 0.0))
      },
    ),
    suite("rebalance")(
      test("publishes a drain window, then commits at the epoch boundary and not before") {
        for
          harness <- steady
          _ <- harness.service.start
          published <- harness.service.rebalance(RebalanceRequest(shardCount = 16, drainMillis = 120_000L))
          _ <- TestClock.adjust(119.seconds)
          _ <- harness.service.settle
          draining <- harness.service.plan
          callsBefore <- harness.rebalancer.calls.get
          _ <- TestClock.adjust(1.second)
          _ <- harness.service.settle
          settled <- harness.service.plan
          callsAfter <- harness.rebalancer.calls.get
        yield assertTrue(
          published.shards.shardCount == 8,
          published.pendingShards.map(_.shardCount) == Some(16),
          published.pendingShards.map(_.drainUntilEpochMillis) == Some(t0.plusSeconds(120).toEpochMilli),
          callsBefore.isEmpty,
          draining.shards == ShardMap(epoch = 0L, shardCount = 8),
          draining.pendingShards.nonEmpty,
          // The rows are rewritten before the map is promoted: a driver must never be told it
          // owns a slice the table does not agree with yet.
          callsAfter == List(16),
          settled.shards == ShardMap(epoch = 1L, shardCount = 16),
          settled.pendingShards.isEmpty,
        )
      },
      test("a failed re-shard keeps the drain in force and is retried, not abandoned") {
        for
          harness <- steady
          _ <- harness.service.start
          _ <- harness.service.rebalance(RebalanceRequest(shardCount = 16, drainMillis = 60_000L))
          _ <- harness.rebalancer.fail
          _ <- TestClock.adjust(60.seconds)
          _ <- harness.service.settle
          stillDraining <- harness.service.plan
          _ <- harness.rebalancer.recover
          _ <- harness.service.settle
          settled <- harness.service.plan
          calls <- harness.rebalancer.calls.get
        yield assertTrue(
          stillDraining.shards.shardCount == 8,
          stillDraining.pendingShards.nonEmpty,
          calls == List(16, 16),
          settled.shards == ShardMap(epoch = 1L, shardCount = 16),
        )
      },
      test("refuses to rebalance a stopped campaign or to publish a second drain") {
        for
          harness <- steady
          _ <- harness.service.start
          _ <- harness.service.rebalance(RebalanceRequest(shardCount = 16, drainMillis = 60_000L))
          second <- harness.service.rebalance(RebalanceRequest(shardCount = 32, drainMillis = 60_000L)).either
          _ <- harness.service.stop
          stopped <- harness.service.rebalance(RebalanceRequest(shardCount = 4, drainMillis = 60_000L)).either
        yield assertTrue(second.isLeft, stopped.isLeft)
      },
    ),
    suite("registration controller")(
      test("winds λ up to the ceiling when the ramp is behind its curve") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make()
          harness <- harness(CoordinatorFixture.registrationConfig, users, snapshots)
          _ <- harness.service.start
          nominal <- harness.service.plan.map(rate(_, PlanScenario.Registration).get.basePerSecond)
          _ <- TestClock.adjust(12.hours)
          _ <- harness.service.controllerTick
          plan <- harness.service.plan
        yield
          val registration = rate(plan, PlanScenario.Registration).get
          assertTrue(
            math.abs(nominal - 3.858) < 0.001,
            // Twelve hours into a 72-hour ramp with nobody registered: the controller is at its
            // clamp, which is what stops one bad reading from running away with the rate.
            math.abs(registration.basePerSecond - nominal * RegistrationController.maxFactor) < 0.001,
          )
      },
      test("winds λ down when the ramp is ahead, and leaves the session streams alone") {
        for
          users <- FakeVirtualUsers.make(
            (1L to 400L).map(CoordinatorFixture.user(_, VirtualUserState.Registered))*,
          )
          snapshots <- FakeMetricSnapshots.make()
          harness <- harness(CoordinatorFixture.registrationConfig, users, snapshots)
          _ <- harness.service.start
          before <- harness.service.plan
          // A minute into the ramp the curve plans a handful of users; 400 exist.
          _ <- TestClock.adjust(1.minute)
          _ <- harness.service.controllerTick
          after <- harness.service.plan
        yield assertTrue(
          rate(after, PlanScenario.Registration).get.basePerSecond <
            rate(before, PlanScenario.Registration).get.basePerSecond,
          rate(after, PlanScenario.MobileSession).get.basePerSecond ==
            rate(before, PlanScenario.MobileSession).get.basePerSecond,
        )
      },
      test("stops the ramp when its duration is up, instead of registering past the target") {
        // The curve clamps at the target, so once the ramp lands the error term is zero and the
        // factor returns to 1.0. Without a separate end to the ramp λ_nominal would go on
        // registering users through every later phase of the campaign, past the population the
        // capacity model is stated against -- §12's "lands the ramp on exactly 1M" is a statement
        // about where it stops.
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make()
          harness <- harness(CoordinatorFixture.registrationConfig, users, snapshots)
          _ <- harness.service.start
          _ <- TestClock.adjust(71.hours)
          _ <- harness.service.controllerTick
          before <- harness.service.plan
          _ <- TestClock.adjust(2.hours)
          _ <- harness.service.controllerTick
          after <- harness.service.plan
        yield assertTrue(
          rate(before, PlanScenario.Registration).get.basePerSecond > 0.0,
          rate(after, PlanScenario.Registration).get.basePerSecond == 0.0,
          rate(after, PlanScenario.Registration).get.ratePerSecond == 0.0,
          // Only the ramp ends. The campaign it was ramping for carries on.
          rate(after, PlanScenario.MobileSession).get.basePerSecond > 0.0,
        )
      },
      test("stops the ramp early once the target exists, rather than overshooting it") {
        for
          users <- FakeVirtualUsers.make(
            (1L to 300L).map(CoordinatorFixture.user(_, VirtualUserState.Registered))*,
          )
          snapshots <- FakeMetricSnapshots.make()
          harness <- harness(CoordinatorFixture.registrationConfigWithTarget(300L), users, snapshots)
          _ <- harness.service.start
          _ <- TestClock.adjust(1.minute)
          _ <- harness.service.controllerTick
          plan <- harness.service.plan
        yield assertTrue(rate(plan, PlanScenario.Registration).get.basePerSecond == 0.0)
      },
      test("a ramp one user short of its target is still running") {
        for
          users <- FakeVirtualUsers.make(
            (1L to 299L).map(CoordinatorFixture.user(_, VirtualUserState.Registered))*,
          )
          snapshots <- FakeMetricSnapshots.make()
          harness <- harness(CoordinatorFixture.registrationConfigWithTarget(300L), users, snapshots)
          _ <- harness.service.start
          _ <- TestClock.adjust(1.minute)
          _ <- harness.service.controllerTick
          plan <- harness.service.plan
        yield assertTrue(rate(plan, PlanScenario.Registration).get.basePerSecond > 0.0)
      },
      test("publishes no registration stream at all for a campaign that registers nobody") {
        for
          harness <- steady
          _ <- harness.service.start
          _ <- TestClock.adjust(12.hours)
          _ <- harness.service.controllerTick
          plan <- harness.service.plan
        yield assertTrue(rate(plan, PlanScenario.Registration).isEmpty)
      },
      test("publishes the population counts it read for the status page") {
        for
          users <- FakeVirtualUsers.make(
            CoordinatorFixture.user(1L, VirtualUserState.Planned),
            CoordinatorFixture.user(2L, VirtualUserState.Registered),
            CoordinatorFixture.user(3L, VirtualUserState.Registered),
            CoordinatorFixture.user(4L, VirtualUserState.Broken),
          )
          snapshots <- FakeMetricSnapshots.make()
          harness <- harness(CoordinatorFixture.coordinatorConfig, users, snapshots)
          _ <- harness.service.controllerTick
          status <- harness.service.status
        yield assertTrue(status.population == Map("planned" -> 1L, "registered" -> 2L, "broken" -> 1L))
      },
    ),
    suite("status")(
      test("reports achieved against planned rate and the fleet's quantiles over the live window") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make(
            CoordinatorFixture.snapshotRow(campaign, "driver-0", t0, tokenRefresh, 90_000L, 100L),
            // Older than the status window, so the live view must not include it even though the
            // report will.
            CoordinatorFixture.snapshotRow(campaign, "driver-0", t0.minusSeconds(3_600), tokenRefresh, 500_000L, 100L),
          )
          harness <- harness(CoordinatorFixture.coordinatorConfig, users, snapshots)
          _ <- harness.service.start
          _ <- harness.service.acceptDriverReport(
            CoordinatorFixture.driverReport(campaign, "driver-0", t0, Map(PlanScenario.MobileSession -> 0L), taxonomy),
          )
          _ <- TestClock.adjust(10.seconds)
          _ <- harness.service.acceptDriverReport(
            CoordinatorFixture.driverReport(
              campaign,
              "driver-0",
              t0.plusSeconds(10),
              Map(PlanScenario.MobileSession -> 90L),
              taxonomy,
            ),
          )
          status <- harness.service.status
        yield
          val mobile = status.scenarios.find(_.scenario == PlanScenario.MobileSession).get
          assertTrue(
            status.state == CampaignState.Running,
            status.phase == Some("warmup"),
            status.drivers == List("driver-0"),
            status.staleDrivers.isEmpty,
            mobile.achievedPerSecond == Some(9.0),
            mobile.plannedPerSecond > 0.0,
            status.latency.map(_.count) == List(100L),
            status.latency.map(_.p99Micros) == List(90_047L),
            status.taxonomy.total == taxonomy.total,
            status.health.maxDriverCpu == Some(0.29),
          )
      },
      test("names a driver still running the map the rebalance replaced") {
        // The one failure mode the drain protocol cannot prevent on its own: the rows have been
        // re-sharded, so a driver on the old epoch is scheduling users another driver now owns,
        // and it is reporting on time like any healthy one.
        for
          harness <- steady
          _ <- harness.service.start
          _ <- harness.service.rebalance(RebalanceRequest(shardCount = 16, drainMillis = 60_000L))
          _ <- TestClock.adjust(61.seconds)
          _ <- harness.service.settle
          _ <- harness.service.acceptDriverReport(
            CoordinatorFixture
              .driverReport(campaign, "driver-0", t0.plusSeconds(61), Map.empty, taxonomy)
              .copy(epoch = 1L),
          )
          _ <- harness.service.acceptDriverReport(
            CoordinatorFixture
              .driverReport(campaign, "driver-1", t0.plusSeconds(61), Map.empty, taxonomy)
              .copy(epoch = 0L),
          )
          status <- harness.service.status
        yield assertTrue(
          status.shards == ShardMap(epoch = 1L, shardCount = 16),
          status.staleDrivers.isEmpty,
          status.staleEpochDrivers == List("driver-1"),
        )
      },
    ),
    suite("report")(
      test("merges the whole campaign and judges it against the configured measurements") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make(
            CoordinatorFixture.snapshotRow(campaign, "driver-0", t0, tokenRefresh, 90_000L, 100L),
            CoordinatorFixture.snapshotRow(campaign, "driver-1", t0, tokenRefresh, 95_000L, 100L),
          )
          harness <- harness(CoordinatorFixture.coordinatorConfig, users, snapshots)
          _ <- harness.service.acceptDriverReport(
            CoordinatorFixture.driverReport(campaign, "driver-0", t0, Map.empty, ErrorTaxonomy.empty),
          )
          report <- harness.service.report(campaign)
        yield assertTrue(
          report.campaign == campaign,
          report.drivers == List("driver-0", "driver-1"),
          report.latency.map(_.count) == List(200L),
          // The token endpoint's p99 is inside the design doc's 120 ms ceiling, and the fleet is
          // healthy -- but edge's relative threshold names measurements nothing recorded, so the
          // campaign cannot pass: not evaluated is not a pass.
          report.checks.find(_.name.contains("token-refresh")).map(_.passed) == Some(true),
          report.notEvaluated.nonEmpty,
          !report.passed,
        )
      },
      test("carries the SUT's database section once both boundaries have been captured") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make(
            CoordinatorFixture.snapshotRow(campaign, "driver-0", t0, tokenRefresh, 90_000L, 100L),
          )
          sutStats <- FakeSutStats.make
          harness <- harnessWith(CoordinatorFixture.coordinatorConfig, users, snapshots, Some(sutStats), None)
          _ <- harness.service.start
          // A run in progress has one reading and no difference, which is not §3 with a hole in
          // it -- it is a statement the report is not yet able to make.
          running <- harness.service.report(campaign)
          _ <- TestClock.adjust(1.hour)
          _ <- harness.service.stop
          stopped <- harness.service.report(campaign)
        yield assertTrue(
          running.databases.isEmpty,
          stopped.databases.map(_.map(_.database)) == Some(List("auth")),
          stopped.databases.exists(_.forall(!_.countersReset)),
          stopped.databases.exists(_.forall(_.counters.isDefined)),
          stopped.databases.exists(_.forall(delta => delta.afterEpochMillis - delta.beforeEpochMillis == 3_600_000L)),
        )
      },
      // A coordinator holding no SUT credentials is a supported deployment: every other section
      // of the report is unaffected by the grant it was not given.
      test("omits the database section entirely when no SUT credentials are configured") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make(
            CoordinatorFixture.snapshotRow(campaign, "driver-0", t0, tokenRefresh, 90_000L, 100L),
          )
          harness <- harness(CoordinatorFixture.coordinatorConfig, users, snapshots)
          _ <- harness.service.start
          _ <- harness.service.stop
          report <- harness.service.report(campaign)
        yield assertTrue(report.databases.isEmpty, report.poolers.isEmpty, report.poolerQueue.isEmpty)
      },
      test("carries the pooler section once both boundaries have been captured") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make(
            CoordinatorFixture.snapshotRow(campaign, "driver-0", t0, tokenRefresh, 90_000L, 100L),
          )
          poolerStats <- FakePoolerStats.make
          harness <- harnessWith(CoordinatorFixture.coordinatorConfig, users, snapshots, None, Some(poolerStats))
          _ <- harness.service.start
          running <- harness.service.report(campaign)
          _ <- TestClock.adjust(1.hour)
          _ <- harness.service.stop
          stopped <- harness.service.report(campaign)
        yield assertTrue(
          running.poolers.isEmpty,
          stopped.poolers.map(_.map(_.pooler)) == Some(List("auth-pooler")),
          stopped.poolers.exists(_.forall(!_.countersRestarted)),
          stopped.poolers.exists(_.forall(_.counters.isDefined)),
        )
      },
      // The half of §4 a bracket cannot answer. Unlike the delta beside it this is already worth
      // reporting mid-run, and it has to stop accumulating when the run does -- a coordinator
      // that lives on for a day after the stop must not report a day of empty readings as the
      // campaign's queue.
      test("samples the pooler queue while the campaign runs, and only while it runs") {
        ZIO.scoped:
          for
            users <- FakeVirtualUsers.make()
            snapshots <- FakeMetricSnapshots.make(
              CoordinatorFixture.snapshotRow(campaign, "driver-0", t0, tokenRefresh, 90_000L, 100L),
            )
            poolerStats <- FakePoolerStats.make
            harness <- harnessWith(CoordinatorFixture.coordinatorConfig, users, snapshots, None, Some(poolerStats))
            _ <- harness.service.run
            _ <- TestClock.adjust(1.minute)
            idle <- poolerStats.sampleCount
            _ <- harness.service.start
            _ <- TestClock.adjust(1.minute)
            running <- harness.service.report(campaign)
            takenWhileRunning <- poolerStats.sampleCount
            _ <- harness.service.pause
            _ <- TestClock.adjust(1.minute)
            takenWhilePaused <- poolerStats.sampleCount
            _ <- harness.service.stop
            _ <- TestClock.adjust(1.minute)
            takenAfterStop <- poolerStats.sampleCount
            stopped <- harness.service.report(campaign)
          yield assertTrue(
            idle == 0,
            takenWhileRunning > 0,
            takenWhilePaused == takenWhileRunning,
            takenAfterStop == takenWhileRunning,
            // The delta needs both boundaries; the peaks do not, which is what makes the section
            // answerable during the run the operator is watching.
            running.poolers.isEmpty,
            running.poolerQueue.map(_.map(_.pooler)) == Some(List("auth-pooler")),
            stopped.poolerQueue.exists(_.forall(_.samples == takenWhileRunning.toLong)),
            // The fake's reading climbs with every sample, so the peak is the last one taken and
            // never the first -- a bracket of the same series would have reported the boundary.
            stopped.poolerQueue.exists(_.forall(_.peakClientsWaiting == takenWhileRunning.toLong)),
          )
      },
      // The two sections are configured independently, so each has to be able to arrive without
      // the other: a developer's stack has databases and no pooler, and a coordinator granted
      // only the pooler's console is the other half of the same case.
      test("the pooler section arrives without the database section, and the reverse") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make(
            CoordinatorFixture.snapshotRow(campaign, "driver-0", t0, tokenRefresh, 90_000L, 100L),
          )
          sutStats <- FakeSutStats.make
          harness <- harnessWith(CoordinatorFixture.coordinatorConfig, users, snapshots, Some(sutStats), None)
          _ <- harness.service.start
          _ <- harness.service.stop
          report <- harness.service.report(campaign)
        yield assertTrue(report.databases.isDefined, report.poolers.isEmpty)
      },
      test("refuses a campaign it is not running, and one with no snapshots at all") {
        for
          harness <- steady
          foreign <- harness.service.report("c7-20m").either
          empty <- harness.service.report(campaign).either
        yield assertTrue(
          foreign.left.toOption.collect { case refusal: CoordinatorRefusal.NotFound => refusal }.nonEmpty,
          empty.left.toOption.collect { case refusal: CoordinatorRefusal.NotFound => refusal }.nonEmpty,
        )
      },
    ),
    suite("boot")(
      test("a coordinator with no plan block does not start") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make()
          rebalancer <- FakeRebalancer.make
          config <- CoordinatorFixture.coordinatorConfig
          refused <- CoordinatorService.make(config.copy(plan = None), users, snapshots, rebalancer, None, None).either
        yield assertTrue(refused.isLeft)
      },
    ),
    suite("sut snapshots")(
      test("the start and the stop of a run are the two boundaries captured") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make()
          sutStats <- FakeSutStats.make
          harness <- harnessWith(CoordinatorFixture.coordinatorConfig, users, snapshots, Some(sutStats), None)
          _ <- harness.service.start
          opening <- sutStats.phases
          _ <- harness.service.stop
          both <- sutStats.phases
        yield assertTrue(
          opening == List(SutStatPhase.Before),
          both == List(SutStatPhase.Before, SutStatPhase.After),
        )
      },
      // A resume runs the same `start` command as a start, and re-capturing there would move the
      // opening reading into the middle of the run -- every counter before it lost.
      test("a pause and a resume are not boundaries") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make()
          sutStats <- FakeSutStats.make
          harness <- harnessWith(CoordinatorFixture.coordinatorConfig, users, snapshots, Some(sutStats), None)
          _ <- harness.service.start
          _ <- TestClock.adjust(10.minutes)
          _ <- harness.service.pause
          _ <- TestClock.adjust(10.minutes)
          _ <- harness.service.start
          phases <- sutStats.phases
          opening <- sutStats.deltas(campaign)
          _ <- harness.service.stop
          deltas <- sutStats.deltas(campaign)
        yield assertTrue(
          phases == List(SutStatPhase.Before),
          opening.isEmpty,
          deltas.map(_.beforeEpochMillis) == List(t0.toEpochMilli),
        )
      },
      // `CampaignControl.stop` is idempotent, and so is the capture behind it: the second call
      // makes no transition, and even if it did the identity index keeps the first reading.
      test("stopping an already stopped campaign captures nothing further") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make()
          sutStats <- FakeSutStats.make
          harness <- harnessWith(CoordinatorFixture.coordinatorConfig, users, snapshots, Some(sutStats), None)
          _ <- harness.service.start
          _ <- harness.service.stop
          _ <- TestClock.adjust(1.hour)
          _ <- harness.service.stop
          phases <- sutStats.phases
          deltas <- sutStats.deltas(campaign)
        yield assertTrue(
          phases == List(SutStatPhase.Before, SutStatPhase.After),
          deltas.map(_.afterEpochMillis) == List(t0.toEpochMilli),
        )
      },
      // §3 and §4 are differenced against each other in the report -- pooler wait against the
      // database's own transaction count -- so the two brackets have to be the same bracket.
      test("the pooler is captured at the same two transitions as the databases") {
        for
          users <- FakeVirtualUsers.make()
          snapshots <- FakeMetricSnapshots.make()
          sutStats <- FakeSutStats.make
          poolerStats <- FakePoolerStats.make
          harness <- harnessWith(
            CoordinatorFixture.coordinatorConfig,
            users,
            snapshots,
            Some(sutStats),
            Some(poolerStats),
          )
          _ <- harness.service.start
          _ <- TestClock.adjust(10.minutes)
          _ <- harness.service.pause
          _ <- harness.service.start
          _ <- harness.service.stop
          databases <- sutStats.phases
          poolers <- poolerStats.phases
        yield assertTrue(
          databases == List(SutStatPhase.Before, SutStatPhase.After),
          poolers == databases,
        )
      },
    ),
  )

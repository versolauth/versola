package versola.loadgen.driver

import versola.loadgen.config.ShardConfig
import versola.loadgen.coordinator.*
import versola.loadgen.metrics.{ErrorTaxonomy, FailedOutcome, IntendedLatency, LatencyRecorder, MeasurementId}
import versola.loadgen.model.{Platform, VirtualUser, VirtualUserState}
import versola.loadgen.scenario.{ArrivalTally, UserPool}
import zio.*
import zio.test.*

/** The driver's lifecycle decisions and the readings it reports, none of which need a SUT.
  *
  * The engine itself is covered by `ScenarioEngineSpec` against a stub SUT; what is left, and
  * what this covers, is everything between the coordinator's plan and that engine -- which is
  * where a driver that quietly generates nothing, or generates the wrong thing, comes from.
  */
object DriverSpec extends ZIOSpecDefault:

  private val eight = ShardMap(epoch = 4L, shardCount = 8)

  def spec = suite("Driver")(
    suite("DriverCommand")(
      test("a running campaign with a start instant is a generation to run") {
        assertTrue(
          DriverCommand.of(plan(CampaignState.Running, Some(1_000L))) ==
            DriverCommand.Run(RunGeneration(1_000L, 8, 4L)),
        )
      },
      test("an idle or paused campaign generates nothing, and a stopped one ends the driver") {
        assertTrue(
          DriverCommand.of(plan(CampaignState.Idle, None)) == DriverCommand.Idle,
          DriverCommand.of(plan(CampaignState.Paused, Some(1_000L))) == DriverCommand.Idle,
          DriverCommand.of(plan(CampaignState.Stopped, Some(1_000L))) == DriverCommand.Stop,
        )
      },
      // Not a state the coordinator publishes. Scheduling against a missing anchor would anchor
      // the arrival recurrence on whatever the driver's clock said, which is the one thing §7.2
      // forbids -- the schedule would exist only as fast as the driver kept up with it.
      test("a running campaign with no start instant is idle, not an error") {
        assertTrue(DriverCommand.of(plan(CampaignState.Running, None)) == DriverCommand.Idle)
      },
      test("a resumed campaign is a new generation, because the anchor moved past the pause") {
        val before = RunGeneration(1_000L, 8, 4L)
        val after = RunGeneration(9_000L, 8, 4L)
        assertTrue(DriverCommand.restartRequired(before, after), !DriverCommand.restartRequired(before, before))
      },
      // A promoted shard map changes both the driver's share of the published rate and the
      // residue class `SessionIds` mints into, so the loop cannot be left running across it.
      test("a promoted shard map is a new generation") {
        assertTrue(
          DriverCommand.restartRequired(RunGeneration(1_000L, 8, 4L), RunGeneration(1_000L, 16, 5L)),
        )
      },
    ),
    suite("published rate")(
      test("both session streams are added, and the registration stream is not scheduled") {
        val published = plan(CampaignState.Running, Some(0L)).copy(scenarios =
          List(
            ScenarioRate(PlanScenario.MobileSession, basePerSecond = 120.0, ratePerSecond = 240.0),
            ScenarioRate(PlanScenario.WebSession, basePerSecond = 30.0, ratePerSecond = 60.0),
            ScenarioRate(PlanScenario.Registration, basePerSecond = 4.0, ratePerSecond = 8.0),
          ),
        )
        assertTrue(Driver.sessionRateOf(published) == 150.0)
      },
      test("a plan that publishes no session rate yields zero rather than a missing key") {
        assertTrue(Driver.sessionRateOf(plan(CampaignState.Running, Some(0L))) == 0.0)
      },
    ),
    suite("identity")(
      // The slice, not the pod: a replaced driver has to report under the id whose tallies the
      // coordinator already holds, or its predecessor's errors leave the campaign's budget.
      test("a driver is named by the shard it owns, and its seed is stable per shard") {
        val shard = ShardConfig(index = 3, count = 8)
        assertTrue(
          Driver.identityOf(shard) == "driver-3",
          Driver.seedOf("c1-1m", shard) == Driver.seedOf("c1-1m", shard),
          Driver.seedOf("c1-1m", shard) != Driver.seedOf("c1-1m", ShardConfig(index = 4, count = 8)),
          Driver.seedOf("c1-1m", shard) != Driver.seedOf("c2-5m", shard),
        )
      },
    ),
    suite("PlanUserPool")(
      test("hands out a user this driver owns") {
        for
          plan <- Ref.make(plan(CampaignState.Running, Some(0L)))
          pool = PlanUserPool.of(FixedPool(List(11L, 19L)), shardIndex = 3, plan)
          first <- pool.next
        yield assertTrue(first.map(_.id) == Some(11L))
      },
      // The drain is the whole safety argument of §12: an outgoing user scheduled one more time
      // is a refresh token two drivers both believe they own.
      test("skips a user that is on its way out under a published rebalance") {
        val draining = plan(CampaignState.Running, Some(0L)).copy(
          pendingShards = Some(ShardMapChange(epoch = 5L, shardCount = 16, drainUntilEpochMillis = 60_000L)),
        )
        for
          planRef <- Ref.make(draining)
          // Both are shard 3 of eight. At sixteen, 11 moves to shard 11 while 19 stays on 3, so
          // 11 is the outgoing one and 19 is the only one still schedulable here.
          pool = PlanUserPool.of(FixedPool(List(11L, 19L)), shardIndex = 3, planRef)
          picked <- pool.next
        yield assertTrue(picked.map(_.id) == Some(19L))
      },
      test("an exhausted slice is reported as empty rather than scanned forever") {
        for
          planRef <- Ref.make(plan(CampaignState.Running, Some(0L)))
          pool = PlanUserPool.of(FixedPool(Nil), shardIndex = 3, planRef)
          picked <- pool.next
        yield assertTrue(picked.isEmpty)
      },
    ),
    suite("ArrivalTally")(
      test("counts cumulatively per platform and maps onto the plan's streams") {
        for
          tally <- ArrivalTally.make
          _ <- tally.record(Platform.Mobile).repeatN(2)
          _ <- tally.record(Platform.Web)
          counted <- tally.cumulative
        yield assertTrue(
          counted == Map(Platform.Mobile -> 3L, Platform.Web -> 1L),
          DriverReporter.arrivalsOf(counted) ==
            Map(PlanScenario.MobileSession -> 3L, PlanScenario.WebSession -> 1L),
        )
      },
    ),
    suite("ScheduleLagQuantile")(
      test("an interval with no samples has no quantile, which is idle rather than on time") {
        for
          quantile <- ScheduleLagQuantile.make
          empty <- quantile.intervalP99Micros
        yield assertTrue(empty.isEmpty)
      },
      // Cumulative would keep reporting the campaign's worst minute for the rest of it, on a
      // reading an operator acts on.
      test("each read covers only the interval since the last one") {
        for
          quantile <- ScheduleLagQuantile.make
          _ <- quantile.sample(2.seconds)
          first <- quantile.intervalP99Micros
          second <- quantile.intervalP99Micros
          _ <- quantile.sample(50.millis)
          third <- quantile.intervalP99Micros
        yield assertTrue(
          first.exists(micros => micros >= 1_900_000L && micros <= 2_100_000L),
          second.isEmpty,
          third.exists(micros => micros >= 45_000L && micros <= 55_000L),
        )
      },
    ),
    suite("LatencyRecorder")(
      // `CampaignHealth.latencyClampedTotal` is how a report says it is understating its own
      // tail. Prometheus counters cannot be read back in process, so the recorder keeps its own.
      test("counts the samples it had to clamp to the top of its range") {
        for
          recorder <- LatencyRecorder.make
          _ <- recorder.record(MeasurementId.Flow("mobile-otp"), IntendedLatency.unsafe(5.millis))
          none <- recorder.clampedTotal
          _ <- recorder.record(MeasurementId.Flow("mobile-otp"), IntendedLatency.unsafe(90.seconds))
          clamped <- recorder.clampedTotal
        yield assertTrue(none == 0L, clamped == 1L)
      },
    ),
    suite("taxonomy on the wire")(
      // §7.4 fixes reuse detection at ~0 so that a non-zero value means something. The verdict
      // reads it off `CampaignHealth`, which must not have to know the taxonomy's shape.
      test("the reported refresh-rejected total is the taxonomy's own count") {
        val taxonomy = ErrorTaxonomy.empty
          .recordMany(versola.loadgen.metrics.StepOutcome.Failed(FailedOutcome.RefreshRejected), 3L)
          .recordMany(versola.loadgen.metrics.StepOutcome.Failed(FailedOutcome.Transport), 5L)
        assertTrue(taxonomy.failedCount(FailedOutcome.RefreshRejected) == 3L, taxonomy.budgetConsumed == 8L)
      },
    ),
  )

  private def plan(state: CampaignState, startedAt: Option[Long]): LoadPlan =
    LoadPlan(
      campaign = "c3-10m-steady",
      state = state,
      phase = Some("steady"),
      startedAtEpochMillis = startedAt,
      publishedAtEpochMillis = 0L,
      pollIntervalMillis = 10_000L,
      scenarios = Nil,
      shards = eight,
      pendingShards = None,
    )

  /** A pool that hands out the ids it was given, in order, and then nothing -- the shape
    * [[UserPool]] has when a driver's slice runs out.
    */
  private final class FixedPool(ids: List[Long]) extends UserPool:
    private val remaining = java.util.concurrent.atomic.AtomicReference(ids)

    override def next: Task[Option[VirtualUser]] =
      ZIO.succeed:
        remaining.getAndUpdate:
          case Nil => Nil
          case _ :: rest => rest
        match
          case Nil => None
          case id :: _ => Some(CoordinatorFixture.user(id, VirtualUserState.Registered))

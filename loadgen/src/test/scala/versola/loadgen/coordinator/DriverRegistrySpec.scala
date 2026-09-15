package versola.loadgen.coordinator

import versola.loadgen.metrics.{ErrorTaxonomy, FailedOutcome, PlannedOutcome, StepOutcome}
import zio.test.*
import zio.*

import java.time.Instant

/** The live fleet view: an achieved rate that is a measurement rather than a restatement of the
  * plan, and a health reading that takes the worst driver rather than the average one.
  */
object DriverRegistrySpec extends ZIOSpecDefault:

  private val campaign = "c3-10m-steady"

  private val t0 = Instant.parse("2026-09-15T08:00:00Z")

  private val taxonomy = ErrorTaxonomy.empty
    .recordMany(StepOutcome.ok, 1_000L)
    .recordMany(StepOutcome.Planned(PlannedOutcome.StepUp), 300L)
    .record(StepOutcome.Failed(FailedOutcome.Transport))

  private def report(driverId: String, at: Instant, mobile: Long, taxonomy: ErrorTaxonomy = taxonomy) =
    CoordinatorFixture.driverReport(campaign, driverId, at, Map(PlanScenario.MobileSession -> mobile), taxonomy)

  private val registry = DriverRegistry.make(campaign, 30.seconds)

  def spec = suite("DriverRegistry")(
    test("a single report gives no rate yet, and a zero would be the wrong guess") {
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        view <- drivers.view(t0, 0L)
      yield assertTrue(
        view.drivers == List("driver-0"),
        view.achievedPerSecond.isEmpty,
        view.taxonomy.total == 1_301L,
      )
    },
    test("two reports are differenced into arrivals per second, summed across the fleet") {
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        _ <- drivers.accept(report("driver-1", t0, 50L))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(10), 200L))
        _ <- drivers.accept(report("driver-1", t0.plusSeconds(10), 100L))
        view <- drivers.view(t0.plusSeconds(10), 0L)
      yield assertTrue(
        view.achievedPerSecond(PlanScenario.MobileSession) == 15.0,
        view.taxonomy.total == 2_602L,
        view.staleDrivers.isEmpty,
      )
    },
    test("a driver whose counters went backwards is re-baselined rather than credited") {
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 5_000L))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(10), 40L))
        restarted <- drivers.view(t0.plusSeconds(10), 0L)
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(20), 140L))
        resumed <- drivers.view(t0.plusSeconds(20), 0L)
      yield assertTrue(
        restarted.achievedPerSecond.isEmpty,
        resumed.achievedPerSecond(PlanScenario.MobileSession) == 10.0,
      )
    },
    test("a restart does not un-record what the driver had already reported") {
      // The failure this is written against is silent and one-directional: the replaced process's
      // tallies vanish, the budget's numerator shrinks, and a campaign that breached its error
      // budget reports that it did not. A lost failure never asks to be investigated.
      val before = ErrorTaxonomy.empty
        .recordMany(StepOutcome.ok, 10_000L)
        .recordMany(StepOutcome.Failed(FailedOutcome.Transport), 600L)
      val after = ErrorTaxonomy.empty
        .recordMany(StepOutcome.ok, 1_000L)
        .recordMany(StepOutcome.Failed(FailedOutcome.Transport), 4L)
      val busy = CoordinatorFixture.healthyVitals.copy(
        refreshRejectedTotal = 3L,
        storeFlushDroppedTotal = 7L,
        latencyClampedTotal = 11L,
      )
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 5_000L, before).copy(vitals = busy))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(10), 40L, after))
        view <- drivers.view(t0.plusSeconds(10), 0L)
      yield assertTrue(
        view.taxonomy.failedCount(FailedOutcome.Transport) == 604L,
        view.taxonomy.total == 11_604L,
        view.taxonomy.budgetConsumed == 604L,
        // The monotone health counters carry across the restart for the same reason; the CPU and
        // schedule-lag readings do not, because those describe a process that no longer exists.
        view.health.refreshRejectedTotal == 3L,
        view.health.flushDroppedTotal == 7L,
        view.health.latencyClampedTotal == 11L,
      )
    },
    test("a second restart carries the first one's tallies too, rather than only the last life") {
      val life = ErrorTaxonomy.empty.recordMany(StepOutcome.Failed(FailedOutcome.Malformed), 5L)
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 900L, life))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(10), 10L, life))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(20), 5L, life))
        view <- drivers.view(t0.plusSeconds(20), 0L)
      yield assertTrue(view.taxonomy.failedCount(FailedOutcome.Malformed) == 15L)
    },
    test("an out-of-order report is dropped, so no interval is ever negative") {
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(10), 200L))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(5), 150L))
        view <- drivers.view(t0.plusSeconds(10), 0L)
      yield assertTrue(view.achievedPerSecond(PlanScenario.MobileSession) == 10.0)
    },
    test("a silent driver is listed as stale but still counted in the taxonomy") {
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        _ <- drivers.accept(report("driver-1", t0, 100L))
        _ <- drivers.accept(report("driver-1", t0.plusSeconds(10), 200L))
        view <- drivers.view(t0.plusSeconds(60), 0L)
      yield assertTrue(
        view.staleDrivers == List("driver-0", "driver-1"),
        // Its steps happened, so they stay in the error budget's denominator: forgetting a
        // replaced pod would shrink the numerator every time the fleet was rolled.
        view.taxonomy.total == 2_602L,
      )
    },
    test("a stale driver's last interval stops counting towards the achieved rate") {
      // A stopped driver whose final two reports are ten arrivals apart would otherwise go on
      // contributing 10/s for the rest of the campaign, so `GET /status` would show a fleet
      // generating load that no process is generating.
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(10), 200L))
        _ <- drivers.accept(report("driver-1", t0.plusSeconds(50), 100L))
        _ <- drivers.accept(report("driver-1", t0.plusSeconds(60), 300L))
        view <- drivers.view(t0.plusSeconds(60), 0L)
      yield assertTrue(
        view.staleDrivers == List("driver-0"),
        view.achievedPerSecond(PlanScenario.MobileSession) == 20.0,
      )
    },
    test("every driver going stale leaves no achieved rate at all, not the rate it had") {
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(10), 200L))
        live <- drivers.view(t0.plusSeconds(10), 0L)
        gone <- drivers.view(t0.plusSeconds(600), 0L)
      yield assertTrue(
        live.achievedPerSecond(PlanScenario.MobileSession) == 10.0,
        gone.achievedPerSecond.isEmpty,
      )
    },
    test("a driver still reporting the old shard map is named, which is the overlap's only signal") {
      // Exactly one process may hold a given refresh token. After the map is promoted the rows
      // have already been re-sharded, so a driver still running the previous epoch is scheduling
      // users someone else now owns -- and it is otherwise indistinguishable from a healthy one,
      // since it keeps reporting on time.
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L).copy(epoch = 4L))
        _ <- drivers.accept(report("driver-1", t0, 100L).copy(epoch = 3L))
        view <- drivers.view(t0, 4L)
      yield assertTrue(
        view.drivers == List("driver-0", "driver-1"),
        view.staleDrivers.isEmpty,
        view.staleEpochDrivers == List("driver-1"),
      )
    },
    test("health takes the fleet's worst CPU and schedule lag, and sums its counters") {
      val saturated = CoordinatorFixture.healthyVitals.copy(
        cpuRatio = Some(0.71),
        scheduleLagP99Micros = Some(900_000L),
        refreshRejectedTotal = 2L,
        storeFlushDroppedTotal = 5L,
        latencyClampedTotal = 1L,
      )
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        _ <- drivers.accept(report("driver-1", t0, 100L).copy(vitals = saturated))
        view <- drivers.view(t0, 0L)
      yield assertTrue(
        view.health.maxDriverCpu == Some(0.71),
        view.health.scheduleLagP99 == Some(Duration.fromMillis(900)),
        view.health.refreshRejectedTotal == 2L,
        view.health.flushDroppedTotal == 5L,
        view.health.latencyClampedTotal == 1L,
      )
    },
    test("refuses another campaign's report and another build's envelope") {
      for
        drivers <- registry
        foreign <- drivers.accept(report("driver-0", t0, 1L).copy(campaign = "c7-20m")).either
        newer <- drivers.accept(report("driver-0", t0, 1L).copy(version = DriverReport.version + 1)).either
        view <- drivers.view(t0, 0L)
      yield assertTrue(foreign.isLeft, newer.isLeft, view.drivers.isEmpty)
    },
    test("a driver with no CPU reading yet leaves the fleet's reading absent rather than zero") {
      for
        drivers <- registry
        _ <- drivers.accept(
          report("driver-0", t0, 1L).copy(vitals =
            CoordinatorFixture.healthyVitals.copy(cpuRatio = None, scheduleLagP99Micros = None),
          ),
        )
        view <- drivers.view(t0, 0L)
      yield assertTrue(view.health.maxDriverCpu.isEmpty, view.health.scheduleLagP99.isEmpty)
    },
  )

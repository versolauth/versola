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
        view <- drivers.view(t0)
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
        view <- drivers.view(t0.plusSeconds(10))
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
        restarted <- drivers.view(t0.plusSeconds(10))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(20), 140L))
        resumed <- drivers.view(t0.plusSeconds(20))
      yield assertTrue(
        restarted.achievedPerSecond.isEmpty,
        resumed.achievedPerSecond(PlanScenario.MobileSession) == 10.0,
      )
    },
    test("an out-of-order report is dropped, so no interval is ever negative") {
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(10), 200L))
        _ <- drivers.accept(report("driver-0", t0.plusSeconds(5), 150L))
        view <- drivers.view(t0.plusSeconds(10))
      yield assertTrue(view.achievedPerSecond(PlanScenario.MobileSession) == 10.0)
    },
    test("a silent driver is listed as stale but still counted in the taxonomy") {
      for
        drivers <- registry
        _ <- drivers.accept(report("driver-0", t0, 100L))
        _ <- drivers.accept(report("driver-1", t0, 100L))
        _ <- drivers.accept(report("driver-1", t0.plusSeconds(10), 200L))
        view <- drivers.view(t0.plusSeconds(60))
      yield assertTrue(
        view.staleDrivers == List("driver-0", "driver-1"),
        // Its steps happened, so they stay in the error budget's denominator: forgetting a
        // replaced pod would shrink the numerator every time the fleet was rolled.
        view.taxonomy.total == 2_602L,
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
        view <- drivers.view(t0)
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
        view <- drivers.view(t0)
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
        view <- drivers.view(t0)
      yield assertTrue(view.health.maxDriverCpu.isEmpty, view.health.scheduleLagP99.isEmpty)
    },
  )

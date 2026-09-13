package versola.loadgen.metrics

import versola.loadgen.protocol.RefreshRejection
import zio.metrics.{Metric, MetricLabel}
import zio.test.*
import zio.{Duration, Ref, UIO, ZIO}

/** Asserts the Prometheus surface's shape: metric names, label sets and label values, plus the
  * counter/gauge distinction. Scenario and step names are made unique per test because the metric
  * registry is process-wide and specs share a JVM -- the same reason `AuthMetricsSpec` does it.
  */
object LoadgenMetricsSpec extends ZIOSpecDefault:

  private def labelSet(labels: Seq[(String, String)]): Set[MetricLabel] =
    labels.map { case (name, value) => MetricLabel(name, value) }.toSet

  private def counter(name: String, labels: (String, String)*): UIO[Double] =
    Metric.counter(name).tagged(labelSet(labels)).value.map(_.count)

  private def histogramCount(name: String, labels: (String, String)*): UIO[Double] =
    Metric.histogram(name, LoadgenMetrics.latencyBoundaries)
      .tagged(labelSet(labels))
      .value
      .map(_.count.toDouble)

  private def gauge(name: String, labels: (String, String)*): UIO[Double] =
    Metric.gauge(name).tagged(labelSet(labels)).value.map(_.value)

  private def unique(prefix: String): UIO[String] =
    zio.Random.nextUUID.map(id => s"$prefix-$id")

  private def healthSource(reading: UIO[DriverHealthSample]): DriverHealthSource =
    new DriverHealthSource:
      override def sample: UIO[DriverHealthSample] = reading

  def spec = suite("LoadgenMetrics")(
    test("counts arrivals per scenario") {
      for
        scenario <- unique("arrivals")
        _ <- LoadgenMetrics.arrival(scenario)
        _ <- LoadgenMetrics.arrival(scenario)
        count <- counter("loadgen_arrivals_total", "scenario" -> scenario)
      yield assertTrue(count == 2.0)
    },
    test("a completed step is timed and counted in the same call") {
      for
        scenario <- unique("step")
        _ <- LoadgenMetrics.stepCompleted(scenario, "proxy-accounts", StepOutcome.ok, IntendedLatency.unsafe(Duration.fromMillis(12)))
        timed <- histogramCount("loadgen_step_duration_seconds", "scenario" -> scenario, "step" -> "proxy-accounts", "outcome" -> "ok")
        counted <- counter("loadgen_outcomes_total", "scenario" -> scenario, "outcome" -> "ok")
      yield assertTrue(timed == 1.0, counted == 1.0)
    },
    test("a step-up outcome is labelled stepup and never lands in a failure bucket") {
      for
        scenario <- unique("stepup")
        _ <- LoadgenMetrics.stepCompleted(scenario, "pay-p2p", StepOutcome.Planned(PlannedOutcome.StepUp), IntendedLatency.unsafe(Duration.fromMillis(30)))
        stepup <- counter("loadgen_outcomes_total", "scenario" -> scenario, "outcome" -> "stepup")
        transport <- counter("loadgen_outcomes_total", "scenario" -> scenario, "outcome" -> "transport")
        unexpected <- counter("loadgen_outcomes_total", "scenario" -> scenario, "outcome" -> "unexpected_status")
      yield assertTrue(stepup == 1.0, transport == 0.0, unexpected == 0.0)
    },
    test("a completed flow is timed but stays out of the step taxonomy") {
      for
        flow <- unique("flow")
        _ <- LoadgenMetrics.flowCompleted(flow, StepOutcome.ok, IntendedLatency.unsafe(Duration.fromMillis(7)))
        timed <- histogramCount("loadgen_flow_duration_seconds", "flow" -> flow, "outcome" -> "ok")
        counted <- counter("loadgen_outcomes_total", "scenario" -> flow, "outcome" -> "ok")
      yield assertTrue(timed == 1.0, counted == 0.0)
    },
    test("schedule lag is a gauge in seconds, per scenario") {
      for
        scenario <- unique("lag")
        _ <- LoadgenMetrics.scheduleLag(scenario, Duration.fromMillis(180))
        lag <- gauge("loadgen_schedule_lag_seconds", "scenario" -> scenario)
      yield assertTrue(lag == 0.18)
    },
    test("refresh rejections are labelled by a bounded reason") {
      for
        before <- counter("loadgen_refresh_rejected_total", "reason" -> "unknown")
        _ <- LoadgenMetrics.refreshRejected(RefreshRejection.Unknown("invalid_grant: reuse of a rotated token"))
        after <- counter("loadgen_refresh_rejected_total", "reason" -> "unknown")
        exchanged <- counter("loadgen_refresh_rejected_total", "reason" -> "already_exchanged")
        _ <- LoadgenMetrics.refreshRejected(RefreshRejection.AlreadyExchanged)
        exchangedAfter <- counter("loadgen_refresh_rejected_total", "reason" -> "already_exchanged")
      yield assertTrue(after - before == 1.0, exchangedAfter - exchanged == 1.0)
    },
    test("population is a gauge per state") {
      for
        _ <- LoadgenMetrics.population(PopulationState.Planned, 1_000_000L)
        _ <- LoadgenMetrics.population(PopulationState.Registered, 998_431L)
        _ <- LoadgenMetrics.population(PopulationState.Broken, 12L)
        planned <- gauge("loadgen_population", "state" -> "planned")
        registered <- gauge("loadgen_population", "state" -> "registered")
        broken <- gauge("loadgen_population", "state" -> "broken")
      yield assertTrue(planned == 1_000_000.0, registered == 998_431.0, broken == 12.0)
    },
    test("dropped write-behind rows only ever move the counter forward") {
      for
        before <- counter("loadgen_store_flush_dropped_total")
        _ <- LoadgenMetrics.storeFlushDropped(4L)
        _ <- LoadgenMetrics.storeFlushDropped(0L)
        _ <- LoadgenMetrics.storeFlushDropped(-3L)
        after <- counter("loadgen_store_flush_dropped_total")
      yield assertTrue(after - before == 4.0)
    },
    suite("DriverHealthReporter")(
      test("publishes every driver-health reading of the spec") {
        val sample = DriverHealthSample(
          scheduleLagByScenario = Map("steady-mobile" -> Duration.fromMillis(120)),
          busyUsers = 4_200,
          inflightRequests = 93,
          storeFlushDroppedTotal = 0L,
          cpuRatio = Some(0.31),
        )
        for
          reporter <- DriverHealthReporter.make(healthSource(ZIO.succeed(sample)))
          _ <- reporter.publish
          lag <- gauge("loadgen_schedule_lag_seconds", "scenario" -> "steady-mobile")
          busy <- gauge("loadgen_busy_users")
          inflight <- gauge("loadgen_inflight_requests")
          cpu <- gauge("loadgen_driver_cpu_ratio")
        yield assertTrue(lag == 0.12, busy == 4200.0, inflight == 93.0, cpu == 0.31)
      },
      test("turns the store's cumulative dropped count into counter increments") {
        for
          cumulative <- Ref.make(0L)
          reporter <- DriverHealthReporter.make(
            healthSource(
              cumulative.get.map(dropped => DriverHealthSample(Map.empty, 0, 0, dropped, None)),
            ),
          )
          before <- counter("loadgen_store_flush_dropped_total")
          _ <- cumulative.set(7L) *> reporter.publish
          afterFirst <- counter("loadgen_store_flush_dropped_total")
          _ <- cumulative.set(11L) *> reporter.publish
          afterSecond <- counter("loadgen_store_flush_dropped_total")
          // The store's own counter restarting must not read as progress.
          _ <- cumulative.set(2L) *> reporter.publish
          afterReset <- counter("loadgen_store_flush_dropped_total")
          _ <- cumulative.set(5L) *> reporter.publish
          afterRebaseline <- counter("loadgen_store_flush_dropped_total")
        yield assertTrue(
          afterFirst - before == 7.0,
          afterSecond - before == 11.0,
          afterReset - before == 11.0,
          afterRebaseline - before == 14.0,
        )
      },
      test("leaves the CPU gauge alone when the JVM has no answer yet") {
        for
          reporter <- DriverHealthReporter.make(
            healthSource(ZIO.succeed(DriverHealthSample(Map.empty, 0, 0, 0L, None))),
          )
          _ <- LoadgenMetrics.driverCpu(0.77)
          _ <- reporter.publish
          cpu <- gauge("loadgen_driver_cpu_ratio")
        yield assertTrue(cpu == 0.77)
      },
      test("the process CPU reading needs two samples, and is a fraction of this pod's allocation") {
        // A single reading has nothing to difference, so the honest answer is None rather than a
        // 0.0 that would read as a perfectly idle driver. The magnitude cannot be asserted on a
        // shared build box; the scale can -- and the scale is the point, since the old
        // `getProcessCpuLoad` divided by every core on the node and so could never reach the 40%
        // gate on a large one.
        for
          cpu <- ProcessCpu.make
          first <- cpu.ratio
          second <- cpu.ratio
        yield assertTrue(first.isEmpty, second.forall(ratio => ratio >= 0.0 && ratio <= 1.0))
      },
    ) @@ TestAspect.sequential,
  ) @@ TestAspect.sequential

package versola.util.postgres

import zio.*
import zio.metrics.*
import zio.test.*

object DbMetricsSpec extends ZIOSpecDefault:

  private def histogramCount(repository: String, operation: String, outcome: String): UIO[Long] =
    Metric
      .histogram("db_client_operation_duration_seconds", DbMetrics.boundaries)
      .tagged(
        MetricLabel("repository", repository),
        MetricLabel("operation", operation),
        MetricLabel("db_system", "postgresql"),
        MetricLabel("outcome", outcome),
      )
      .value
      .map(_.count)

  // Repo-shaped helper: the call-site trace must resolve `repository` to this object's simple name.
  private def autoDerivedOp: Task[Int] =
    DbMetrics.measured("auto-op")(ZIO.succeed(1))

  private def poolLabels(poolName: String) =
    Set(MetricLabel("db_system", "postgresql"), MetricLabel("pool_name", poolName))

  private def waitHistogram(poolName: String) =
    Metric.histogram("db_client_connection_wait_time_seconds", DbMetrics.connectionWaitBoundaries).tagged(poolLabels(poolName))

  private def gauge(name: String, poolName: String, extraLabels: MetricLabel*): UIO[Double] =
    Metric.gauge(name).tagged(poolLabels(poolName) ++ extraLabels).value.map(_.value)

  private def buckets = ConnectionWaitBuckets(DbMetrics.connectionWaitBoundaries.values)

  /** Waits spanning the whole range the boundaries cover: below the first boundary, exactly on it,
    * several times inside one bucket so a mean has something to average, and past the last finite
    * boundary (~26 s) so the overflow bucket is exercised too.
    */
  private val waitNanos =
    Chunk(12_000L, 100_000L, 260_000L, 310_000L, 470_000L, 1_200_000L, 41_000_000L, 780_000_000L, 31_000_000_000L)

  private def replay(poolName: String, drained: ConnectionWaitBuckets.Drained): UIO[Unit] =
    ZIO.foreachDiscard(drained.replays): (seconds, count) =>
      DbMetrics.connectionWait(poolName, seconds).repeatN((count - 1L).toInt)

  def spec = suite("DbMetrics")(
    suite("repositoryName")(
      test("derives the simple class name from a method location") {
        val trace =
          Trace.apply("versola.configuration.themes.PostgresThemeRepository.getAll", "PostgresThemeRepository.scala", 10)
        assertTrue(DbMetrics.repositoryName(trace) == "versola.configuration.themes.PostgresThemeRepository.getAll")
      },
      test("strips a trailing $ from object owners") {
        val trace = Trace.apply("versola.configuration.themes.ThemeQueries$.getAll", "ThemeQueries.scala", 5)
        assertTrue(DbMetrics.repositoryName(trace) == "versola.configuration.themes.ThemeQueries.getAll")
      },
      test("falls back to unknown for the empty trace") {
        assertTrue(DbMetrics.repositoryName(Trace.empty) == "unknown")
      },
    ),
    test("records a success outcome with the derived repository and given operation") {
      val trace = Trace.apply("versola.test.SuccessRepo.run", "SuccessRepo.scala", 1)
      for
        _ <- DbMetrics.measured("success-op")(ZIO.succeed(()))(using trace)
        count <- histogramCount("versola.test.SuccessRepo.run", "success-op", "success")
      yield assertTrue(count == 1L)
    },
    test("records a failure outcome and re-raises the original error") {
      val trace = Trace.apply("versola.test.FailureRepo.run", "FailureRepo.scala", 1)
      val boom = new RuntimeException("boom")
      for
        exit <- DbMetrics.measured("failure-op")(ZIO.fail(boom))(using trace).exit
        count <- histogramCount("versola.test.FailureRepo.run", "failure-op", "failure")
      yield assertTrue(exit == Exit.fail(boom), count == 1L)
    },
    test("auto-derives the repository from the enclosing class") {
      for
        result <- autoDerivedOp
        count <- histogramCount("versola.util.postgres.DbMetricsSpec.autoDerivedOp", "auto-op", "success")
      yield assertTrue(result == 1, count == 1L)
    },
    suite("ConnectionWaitBuckets")(
      test("replaying a drain reproduces the buckets, count and sum of the raw observations") {
        val pool = "replay-equivalence"
        // The claim [[ConnectionWaitBuckets]] rests on, checked against the only authority on it:
        // a histogram of the same shape fed every observation individually.
        val reference = Metric.histogram("db_metrics_spec_reference_wait_seconds", DbMetrics.connectionWaitBoundaries)
        val accumulator = buckets
        for
          _ <- ZIO.foreachDiscard(waitNanos): nanos =>
            ZIO.succeed(accumulator.record(nanos)) *> reference.update(nanos.toDouble / 1e9)
          _ <- replay(pool, accumulator.drain())
          replayed <- waitHistogram(pool).value
          expected <- reference.value
        yield assertTrue(
          replayed.buckets == expected.buckets,
          replayed.count == expected.count,
          math.abs(replayed.sum - expected.sum) <= 1e-9 * expected.sum,
        )
      },
      test("a drain covers only what arrived since the previous one") {
        val accumulator = buckets
        accumulator.record(500_000L)
        val first = accumulator.drain()
        val second = accumulator.drain()
        accumulator.record(500_000L)
        accumulator.record(500_000L)
        val third = accumulator.drain()
        assertTrue(
          first.replays.map(_._2).sum == 1L,
          second.replays.isEmpty,
          third.replays.map(_._2).sum == 2L,
        )
      },
      test("a wait past the last finite boundary keeps its own value rather than being clamped to it") {
        val accumulator = buckets
        accumulator.record(45_000_000_000L)
        val replays = accumulator.drain().replays
        assertTrue(replays == Chunk(45.0 -> 1L))
      },
      test("timeouts are reported as a delta, so a counter can add them") {
        val accumulator = buckets
        accumulator.recordTimeout()
        accumulator.recordTimeout()
        val first = accumulator.drain().timeouts
        accumulator.recordTimeout()
        val second = accumulator.drain().timeouts
        assertTrue(first == 2L, second == 1L)
      },
    ),
    suite("pool metrics")(
      test("poolOccupancy splits the connection count by state and publishes the pool's limits") {
        val pool = "occupancy"
        for
          _ <- DbMetrics.poolOccupancy(pool, used = 7, idle = 3, max = 15, idleMin = 5, pendingRequests = 2)
          used <- gauge("db_client_connection_count", pool, MetricLabel("state", "used"))
          idle <- gauge("db_client_connection_count", pool, MetricLabel("state", "idle"))
          max <- gauge("db_client_connection_max", pool)
          idleMin <- gauge("db_client_connection_idle_min", pool)
          pending <- gauge("db_client_connection_pending_requests", pool)
        yield assertTrue(used == 7.0, idle == 3.0, max == 15.0, idleMin == 5.0, pending == 2.0)
      },
      test("connectionTimeouts adds increments and ignores a non-positive one") {
        val pool = "timeouts"
        val counter = Metric.counter("db_client_connection_timeouts_total").tagged(poolLabels(pool))
        for
          _ <- DbMetrics.connectionTimeouts(pool, 3L)
          _ <- DbMetrics.connectionTimeouts(pool, 0L)
          _ <- DbMetrics.connectionTimeouts(pool, 2L)
          count <- counter.value.map(_.count)
        yield assertTrue(count == 5.0)
      },
    ),
  )

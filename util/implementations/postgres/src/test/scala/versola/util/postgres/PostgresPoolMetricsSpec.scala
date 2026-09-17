package versola.util.postgres

import com.zaxxer.hikari.HikariDataSource
import versola.util.Secret
import zio.*
import zio.metrics.*
import zio.test.*

/** Exercises the pool metrics against a real pool, because the thing that was missing was never the
  * arithmetic -- it was that HikariCP had not been asked for the readings at all. Only a pool that
  * actually hands out a connection can show that the tracker is installed and that the MXBean is
  * reachable without `registerMbeans`.
  */
object PostgresPoolMetricsSpec extends ZIOSpecDefault:

  private val interval = 1.second

  private def config(poolName: String, poolMetricsInterval: Option[Duration]) =
    ZLayer.fromZIO:
      System.env("POSTGRES_HOST").someOrElse("localhost:5432").map: host =>
        PostgresConfig(
          url = s"jdbc:postgresql://$host/auth",
          notificationsUrl = None,
          user = "dev",
          password = Secret.fromString("1234"),
          maximumPoolSize = 2,
          minimumIdle = 1,
          connectionTimeout = 30.seconds,
          maxLifetime = 30.minutes,
          leakDetectionThreshold = Duration.Zero,
          poolMetricsInterval = poolMetricsInterval,
        )

  private def pool(poolName: String, poolMetricsInterval: Option[Duration]): ZIO[Scope, Throwable, HikariDataSource] =
    ZIO.serviceWithZIO[HikariDataSource](ZIO.succeed(_)).provideSome[Scope](
      config(poolName, poolMetricsInterval),
      PostgresHikariDataSource.layer(serviceName = Some(poolName), migrate = true, validateOnMigrate = false),
    )

  /** Borrow and return a connection, which is what produces an acquisition to be timed. */
  private def borrow(dataSource: HikariDataSource) =
    ZIO.attemptBlocking(dataSource.getConnection().close())

  private def poolLabels(poolName: String) =
    Set(MetricLabel("db_system", "postgresql"), MetricLabel("pool_name", poolName))

  private def waitCount(poolName: String): UIO[Long] =
    Metric
      .histogram("db_client_connection_wait_time_seconds", DbMetrics.connectionWaitBoundaries)
      .tagged(poolLabels(poolName))
      .value
      .map(_.count)

  private def gauge(name: String, poolName: String, extraLabels: MetricLabel*): UIO[Double] =
    Metric.gauge(name).tagged(poolLabels(poolName) ++ extraLabels).value.map(_.value)

  def spec = suite("pool metrics over a live pool")(
    test("publishes occupancy and times acquisitions once the interval has opted in") {
      val poolName = "live-opted-in"
      for
        dataSource <- pool(poolName, Some(interval))
        _ <- borrow(dataSource)
        // One interval plus margin: the first reading is published on acquisition, before the
        // connection above was ever taken.
        _ <- ZIO.sleep(interval + 500.millis)
        waits <- waitCount(poolName)
        max <- gauge("db_client_connection_max", poolName)
        idleMin <- gauge("db_client_connection_idle_min", poolName)
        idle <- gauge("db_client_connection_count", poolName, MetricLabel("state", "idle"))
        pending <- gauge("db_client_connection_pending_requests", poolName)
      yield assertTrue(waits >= 1L, max == 2.0, idleMin == 1.0, idle >= 1.0, pending == 0.0)
    },
    test("installs no tracker and publishes nothing when the interval is absent") {
      val poolName = "live-opted-out"
      for
        dataSource <- pool(poolName, None)
        _ <- borrow(dataSource)
        _ <- ZIO.sleep(interval + 500.millis)
        waits <- waitCount(poolName)
        max <- gauge("db_client_connection_max", poolName)
      yield assertTrue(dataSource.getMetricsTrackerFactory == null, waits == 0L, max == 0.0)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

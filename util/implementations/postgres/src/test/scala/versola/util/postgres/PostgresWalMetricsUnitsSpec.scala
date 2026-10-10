package versola.util.postgres

import zio.*
import zio.metrics.Metric
import zio.test.*

import PostgresWalMetrics.Cumulative

object PostgresWalMetricsUnitsSpec extends ZIOSpecDefault:

  private def total(name: String): UIO[Double] = Metric.counterDouble(name).value.map(_.count)

  def spec = suite("PostgresWalMetrics")(
    suite("units")(
      test("sizes are converted to bytes") {
        assertTrue(
          PostgresWalMetrics.bytes(1024, "MB").contains(1073741824.0),
          PostgresWalMetrics.bytes(1, "GB").contains(1073741824.0),
          PostgresWalMetrics.bytes(128, "8kB").contains(1048576.0),
          PostgresWalMetrics.bytes(16, "kB").contains(16384.0),
          PostgresWalMetrics.bytes(5, "s").isEmpty,
        )
      },
      test("durations are converted to seconds") {
        assertTrue(
          PostgresWalMetrics.seconds(5, "min").contains(300.0),
          PostgresWalMetrics.seconds(300, "s").contains(300.0),
          PostgresWalMetrics.seconds(200, "ms").contains(0.2),
          PostgresWalMetrics.seconds(1, "h").contains(3600.0),
          PostgresWalMetrics.seconds(1, "MB").isEmpty,
        )
      },
    ),
    suite("republishing a cumulative number as a counter")(
      test("the first reading is a baseline, later ones add the difference") {
        for
          cumulative <- Cumulative.make(Metric.counterDouble("test_wal_cumulative_a"))
          _ <- cumulative.observe(1000)
          afterBaseline <- total("test_wal_cumulative_a")
          _ <- cumulative.observe(1300)
          _ <- cumulative.observe(1300)
          _ <- cumulative.observe(1350)
          afterGrowth <- total("test_wal_cumulative_a")
        yield assertTrue(afterBaseline == 0.0, afterGrowth == 350.0)
      },
      test("a reading below the previous one is a reset, and counts in full") {
        for
          cumulative <- Cumulative.make(Metric.counterDouble("test_wal_cumulative_b"))
          _ <- cumulative.observe(500)
          _ <- cumulative.observe(800)
          _ <- cumulative.observe(20)
          _ <- cumulative.observe(70)
          counted <- total("test_wal_cumulative_b")
        yield assertTrue(counted == 300.0 + 20.0 + 50.0)
      },
    ),
  )

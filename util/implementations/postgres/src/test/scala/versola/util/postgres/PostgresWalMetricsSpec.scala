package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.{SqlLiteral, sql}
import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

/** Against a real Postgres: the readings are the database's own, so they are compared with what the database
  * says when asked another way. The test role must be allowed to run CHECKPOINT and create replication slots,
  * which the development database's superuser is. */
object PostgresWalMetricsSpec extends PostgresSpec:

  private def counter(name: String, labels: MetricLabel*): UIO[Double] =
    Metric.counterDouble(name).tagged(labels.toSet).value.map(_.count)

  private def gauge(name: String, labels: MetricLabel*): UIO[Double] =
    Metric.gauge(name).tagged(labels.toSet).value.map(_.value)

  override val spec: Spec[TransactorZIO & TestEnvironment & Scope, Any] =
    suite("PostgresWalMetrics.Sampler")(
      test("publishes max_wal_size and checkpoint_timeout as the database computes them") {
        for
          xa <- ZIO.service[TransactorZIO]
          sampler <- PostgresWalMetrics.Sampler.make(xa)
          _ <- sampler.sample
          expectedSize <- xa.connect(sql"SELECT pg_size_bytes(current_setting('max_wal_size'))::float8".query[Double].run().head)
          expectedTimeout <- xa.connect(
            sql"SELECT EXTRACT(EPOCH FROM current_setting('checkpoint_timeout')::interval)::float8".query[Double].run().head,
          )
          size <- gauge("db_wal_max_size_bytes")
          timeout <- gauge("db_checkpoint_timeout_seconds")
        yield assertTrue(size == expectedSize, timeout == expectedTimeout, size > 0.0, timeout > 0.0)
      },
      test("counts the WAL written between two samples") {
        for
          xa <- ZIO.service[TransactorZIO]
          sampler <- PostgresWalMetrics.Sampler.make(xa)
          _ <- sampler.sample
          before <- counter("db_wal_bytes_total")
          recordsBefore <- counter("db_wal_records_total")
          name = s"wal_probe_${java.util.UUID.randomUUID().toString.replace("-", "")}"
          table = SqlLiteral(name)
          _ <- xa.connect:
            sql"CREATE TABLE $table (id INT PRIMARY KEY, payload TEXT)".update.run()
            sql"INSERT INTO $table SELECT g, repeat('x', 200) FROM generate_series(1, 5000) g".update.run()
          .ensuring(xa.connect(sql"DROP TABLE IF EXISTS $table".update.run()).ignore)
          // a backend hands its WAL statistics to shared memory at most about once a second; the real
          // sampler runs every minute and never notices, a test that samples at once does
          _ <- Live.live(ZIO.sleep(2.seconds))
          _ <- sampler.sample
          after <- counter("db_wal_bytes_total")
          recordsAfter <- counter("db_wal_records_total")
        yield assertTrue(after - before > 100_000.0, recordsAfter > recordsBefore)
      },
      test("counts a checkpoint taken on request") {
        for
          xa <- ZIO.service[TransactorZIO]
          sampler <- PostgresWalMetrics.Sampler.make(xa)
          _ <- sampler.sample
          before <- counter("db_checkpoints_total", MetricLabel("type", "requested"))
          _ <- xa.connect(sql"CHECKPOINT".update.run())
          _ <- sampler.sample
          after <- counter("db_checkpoints_total", MetricLabel("type", "requested"))
        yield assertTrue(after - before >= 1.0)
      },
      test("reports the WAL an idle replication slot is holding back, and zeroes it once the slot is dropped") {
        val slot = s"wal_probe_slot_${java.util.UUID.randomUUID().toString.replace("-", "")}"
        val label = MetricLabel("slot", slot)
        for
          xa <- ZIO.service[TransactorZIO]
          sampler <- PostgresWalMetrics.Sampler.make(xa)
          _ <- xa.connect(sql"SELECT pg_create_physical_replication_slot($slot, true)".query[String].run())
          result <- (for
            _ <- xa.connect(sql"SELECT pg_switch_wal()".query[String].run())
            _ <- sampler.sample
            retained <- gauge("db_replication_slot_retained_bytes", label)
            active <- gauge("db_replication_slot_active", label)
            worst <- gauge("db_wal_retained_by_slots_bytes")
          yield (retained, active, worst))
            .ensuring(xa.connect(sql"SELECT pg_drop_replication_slot($slot)".query[String].run()).ignore)
          _ <- sampler.sample
          afterDrop <- gauge("db_replication_slot_retained_bytes", label)
        yield assertTrue(result._1 > 0.0, result._2 == 0.0, result._3 >= result._1, afterDrop == 0.0)
      },
    ) @@ TestAspect.sequential

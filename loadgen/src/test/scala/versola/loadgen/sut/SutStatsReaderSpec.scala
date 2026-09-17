package versola.loadgen.sut

import versola.loadgen.seed.{SutDatabases, SutSchema}
import zio.*
import zio.test.*

import java.sql.Connection

/** The queries themselves, against a real Postgres -- the SUT's own migrated `auth` database, the
  * one [[SutDatabases]] stands up for the seeder.
  *
  * There is nothing here a fake could check. Every risk in this reader is a property of the
  * server it is pointed at: whether the view exists on this major, whether the column is spelled
  * the way the release notes say, whether `pg_stat_user_tables` has moved the churn of a table
  * this test just wrote to. A stub returning a `SutStats` would confirm the reader's own
  * assumptions and nothing else.
  *
  * What it cannot check is the other side of the version matrix: the suite runs against one
  * server, so the assertions below are written against *its* [[PostgresVersion]] rather than
  * against a fixed major. [[PostgresVersionSpec]] pins which columns each major is asked for.
  */
object SutStatsReaderSpec extends ZIOSpecDefault:

  override def aspects = super.aspects ++ Chunk(TestAspect.sequential, TestAspect.withLiveClock)

  private val probe = "sut_stats_probe"

  /** The statistics collector does not publish a backend's counters on commit: it flushes them at
    * most every `PGSTAT_MIN_INTERVAL`, so a snapshot taken immediately after a write can legally
    * be taken before that write is visible to `pg_stat_user_tables`. Polling for the figure the
    * test wrote is the only way to assert on it without either a `sleep` chosen by guess or an
    * assertion weak enough to pass on a reader that reads nothing.
    */
  private def untilFlushed(connection: Connection)(reached: SutStatsReading => Boolean): Task[SutStatsReading] =
    (ZIO.sleep(100.millis) *> SutStatsReader.read(connection))
      .repeatUntil(reached)
      .timeoutFail(IllegalStateException("the statistics collector never published the writes this test made"))(
        20.seconds,
      )

  private def probeTable(reading: SutStatsReading): Option[SutTableStats] =
    reading.stats.counters.tables.find(_.table == probe)

  def spec = suite("SutStatsReader")(
    test("reads every section the server's major version has") {
      ZIO.scoped:
        for
          config <- SutDatabases.prepare(SutDatabases.authDatabase, SutSchema.SchemaOwner.Auth.migrationsDirectory)
          connection <- SutDatabases.connect(config)
          reading <- SutStatsReader.read(connection)
        yield
          val version = PostgresVersion(reading.serverVersionNum)
          val counters = reading.stats.counters
          assertTrue(
            version.major >= PostgresVersion.oldestWithWalStats,
            counters.wal.isDefined == version.hasStatWal,
            counters.walIo.isDefined == version.hasStatIo,
            // Neither view is optional: one of the two spellings of the checkpoint counters
            // exists on every major, which is the whole point of reading both.
            counters.checkpointer.map(_.view) ==
              Some(if version.hasStatCheckpointer then "pg_stat_checkpointer" else "pg_stat_bgwriter"),
            counters.checkpointer.flatMap(_.done).isDefined == version.hasCheckpointerTotals,
            counters.walIo.flatMap(_.writeBytes).isDefined == version.hasStatIoBytes,
            counters.tables.forall(_.autovacuumTimeMillis.isDefined == version.hasVacuumTimes),
            // The checkpoint view always has a `stats_reset`, whichever of the two spellings
            // answered; `pg_stat_io`'s only exists once the view itself does.
            reading.checkpointerStatsResetAt.isDefined,
            reading.walIoStatsResetAt.isDefined == version.hasStatIo,
            reading.statementsStatsResetAt.isDefined == counters.statements.isDefined,
            // auth's schema, migrated by SutDatabases: the table the campaign is actually about.
            counters.tables.exists(_.table == "refresh_tokens"),
            reading.stats.gauges.databaseSizeBytes > 0L,
            reading.stats.gauges.backends >= 1L,
            reading.stats.gauges.maxConnections > 0,
          )
    },
    // The WAL section is the one 07-wal-tuning.md is written around, and `wal_bytes` is the
    // figure its whole cycle is judged on, so "the column was readable" is asserted separately
    // from "the sections line up with the version".
    test("a write moves pg_stat_wal between two readings") {
      ZIO.scoped:
        for
          config <- SutDatabases.prepare(SutDatabases.authDatabase, SutSchema.SchemaOwner.Auth.migrationsDirectory)
          connection <- SutDatabases.connect(config)
          _ <- SutDatabases.statement(connection, s"DROP TABLE IF EXISTS $probe")
          _ <- SutDatabases.statement(connection, s"CREATE TABLE $probe (id BIGINT PRIMARY KEY, payload TEXT NOT NULL)")
          before <- untilFlushed(connection)(_.stats.counters.wal.isDefined)
          _ <- SutDatabases.statement(
            connection,
            s"INSERT INTO $probe SELECT generate_series(1, 2000), repeat('x', 200)",
          )
          opening = before.stats.counters.wal.get
          after <- untilFlushed(connection)(_.stats.counters.wal.exists(_.bytes > opening.bytes))
          _ <- SutDatabases.statement(connection, s"DROP TABLE $probe")
        yield
          val closing = after.stats.counters.wal.get
          assertTrue(closing.bytes > opening.bytes, closing.records > opening.records)
    },
    test("reports a table's churn as the difference between two readings") {
      ZIO.scoped:
        for
          config <- SutDatabases.prepare(SutDatabases.authDatabase, SutSchema.SchemaOwner.Auth.migrationsDirectory)
          connection <- SutDatabases.connect(config)
          _ <- SutDatabases.statement(connection, s"DROP TABLE IF EXISTS $probe")
          _ <- SutDatabases.statement(connection, s"CREATE TABLE $probe (id BIGINT PRIMARY KEY, payload TEXT NOT NULL)")
          _ <- SutDatabases.statement(connection, s"INSERT INTO $probe SELECT generate_series(1, 10), 'seed'")
          before <- untilFlushed(connection)(probeTable(_).exists(_.rowsInserted >= 10L))
          _ <- SutDatabases.statement(connection, s"INSERT INTO $probe SELECT generate_series(11, 17), 'run'")
          _ <- SutDatabases.statement(connection, s"UPDATE $probe SET payload = 'touched' WHERE id <= 3")
          _ <- SutDatabases.statement(connection, s"DELETE FROM $probe WHERE id > 15")
          after <- untilFlushed(connection)(probeTable(_).exists(_.rowsDeleted >= 2L))
          _ <- SutDatabases.statement(connection, s"DROP TABLE $probe")
        yield
          val start = probeTable(before).get
          val end = probeTable(after).get
          assertTrue(
            end.rowsInserted - start.rowsInserted == 7L,
            end.rowsUpdated - start.rowsUpdated == 3L,
            end.rowsDeleted - start.rowsDeleted == 2L,
            // The UPDATE touches no indexed column on a freshly written page, so it is the HOT
            // case 07-wal-tuning.md measures `refresh_tokens` against.
            end.rowsHotUpdated - start.rowsHotUpdated == 3L,
          )
    },
    // §3's "top-5 queries" is the one row of the report that is absent rather than wrong when
    // the extension is not installed: it needs `shared_preload_libraries`, which is a restart of
    // the cluster and not something a campaign can arrange for itself.
    test("degrades to no statements section when pg_stat_statements is not installed") {
      ZIO.scoped:
        for
          config <- SutDatabases.prepare(SutDatabases.authDatabase, SutSchema.SchemaOwner.Auth.migrationsDirectory)
          connection <- SutDatabases.connect(config)
          reading <- SutStatsReader.read(connection)
          installed <- ZIO.attemptBlocking:
            val statement = connection.prepareStatement("SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'")
            try
              val rows = statement.executeQuery()
              try rows.next()
              finally rows.close()
            finally statement.close()
        yield assertTrue(reading.stats.counters.statements.isDefined == installed)
    },
    // `pg_extension` alone answers "was `CREATE EXTENSION` ever run", not "is the library in
    // `shared_preload_libraries`" -- that one needs a cluster restart, `CREATE EXTENSION` does
    // not. Creating the extension without it reproduces the gap directly: `pg_extension` says
    // present, the view itself raises "must be loaded via shared_preload_libraries", and that
    // must degrade this one section rather than fail the whole reading.
    test("degrades to no statements section, not a failed read, when the extension is created without shared_preload_libraries") {
      ZIO.scoped:
        for
          config <- SutDatabases.prepare(SutDatabases.authDatabase, SutSchema.SchemaOwner.Auth.migrationsDirectory)
          connection <- SutDatabases.connect(config)
          preloaded <- ZIO.attemptBlocking:
            val statement = connection.prepareStatement("SHOW shared_preload_libraries")
            try
              val rows = statement.executeQuery()
              try
                rows.next()
                rows.getString(1).contains("pg_stat_statements")
              finally rows.close()
            finally statement.close()
          _ <- ZIO.acquireRelease(SutDatabases.statement(connection, "CREATE EXTENSION IF NOT EXISTS pg_stat_statements")): _ =>
            SutDatabases.statement(connection, "DROP EXTENSION IF EXISTS pg_stat_statements").orDie
          reading <- SutStatsReader.read(connection)
        yield assertTrue(reading.stats.counters.statements.isDefined == preloaded)
    },
  ) @@ TestAspect.timeout(3.minutes)

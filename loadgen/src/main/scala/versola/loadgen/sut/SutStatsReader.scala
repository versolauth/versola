package versola.loadgen.sut

import zio.{Task, ZIO}

import java.sql.{Connection, ResultSet, SQLException, Timestamp}
import java.time.Instant

/** One `pg_stat_*` reading of one SUT database, as [[SutStatsReader]] takes it: the statistics
  * plus everything needed to decide later whether a pair of them may be differenced.
  */
case class SutStatsReading(
    serverVersionNum: Int,
    statsResetAt: Option[Instant],
    walStatsResetAt: Option[Instant],
    checkpointerStatsResetAt: Option[Instant],
    walIoStatsResetAt: Option[Instant],
    statementsStatsResetAt: Option[Instant],
    stats: SutStats,
)

/** Reads the views 05-report-spec.md §3 and 07-wal-tuning.md name, from a plain JDBC connection
  * to a system-under-test database.
  *
  * Plain JDBC and one connection, like the seeder's [[versola.loadgen.seed.CopySink.OfConnection]]
  * and for a related reason: this runs twice per campaign, so a pool against the SUT would spend a
  * ten-hour run holding idle connections that show up in the very `pg_stat_activity` count this
  * reads, and in the SUT's own connection budget, to serve two queries.
  *
  * Every statement is read against [[PostgresVersion]] first. The alternative -- one `SELECT *`
  * per view and picking columns out of the result -- would survive a rename, but it would also
  * silently report `None` for a statistic whose column was merely spelled differently, which is
  * the failure this whole capture exists to prevent.
  */
object SutStatsReader:

  /** How many `pg_stat_statements` rows [[SutStatsDelta]] keeps in a campaign's difference.
    * §3 shows five; kept at 20 here for the same reason it always was -- the top five *of the
    * run* are a difference, and a statement can be sixth by the run's own time and first by
    * cumulative time, or the other way round.
    *
    * Not a `LIMIT` on what this reader *captures*: see [[statementRows]] for why capturing only
    * the top N by cumulative time, at each boundary independently, does not give this limit
    * anything correct to keep. The cut this constant names happens once, after differencing, in
    * [[SutStatsDelta]].
    */
  val statementLimit: Int = 20

  /** Normalised query texts are unbounded -- a generated `IN` list runs to kilobytes -- and two
    * snapshots of them are stored per campaign. The identity the report matches on is `queryid`,
    * not this, so truncation costs nothing but the tail of a long statement.
    */
  val queryTextLimit: Int = 1000

  /** Bounds each statement rather than the capture as a whole. A campaign boundary waits on this,
    * so an unreachable or wedged SUT database has to fail the snapshot instead of holding the
    * operator's `POST /campaign/stop` open for as long as the socket stays up.
    */
  val queryTimeoutSeconds: Int = 15

  def read(connection: Connection): Task[SutStatsReading] =
    ZIO.attemptBlocking:
      val version = PostgresVersion(
        one(connection, "SELECT current_setting('server_version_num')::int AS server_version_num")(
          _.getInt("server_version_num"),
        ).getOrElse(throw IllegalStateException("the SUT database did not report a server_version_num")),
      )
      val (activity, statsResetAt) = databaseActivity(connection)
      val (wal, walStatsResetAt) = if version.hasStatWal then walStats(connection) else (None, None)
      val (checkpointerStats, checkpointerStatsResetAt) = checkpointer(connection, version)
      val (walIoStats, walIoStatsResetAt) =
        if version.hasStatIo then walIo(connection, version) else (None, None)
      val (statementRows, statementsStatsResetAt) = statements(connection) match
        case Some((rows, resetAt)) => (Some(rows), resetAt)
        case None => (None, None)
      SutStatsReading(
        serverVersionNum = version.serverVersionNum,
        statsResetAt = statsResetAt,
        walStatsResetAt = walStatsResetAt,
        checkpointerStatsResetAt = checkpointerStatsResetAt,
        walIoStatsResetAt = walIoStatsResetAt,
        statementsStatsResetAt = statementsStatsResetAt,
        stats = SutStats(
          counters = SutCounters(
            wal = wal,
            checkpointer = checkpointerStats,
            walIo = walIoStats,
            database = activity,
            tables = tables(connection, version),
            statements = statementRows,
          ),
          gauges = gauges(connection),
        ),
      )

  private def walStats(connection: Connection): (Option[SutWalStats], Option[Instant]) =
    val read = one(
      connection,
      "SELECT wal_records, wal_fpi, wal_bytes, wal_buffers_full, stats_reset FROM pg_stat_wal",
    ): row =>
      (
        SutWalStats(
          records = row.getLong("wal_records"),
          fullPageImages = row.getLong("wal_fpi"),
          bytes = row.getLong("wal_bytes"),
          buffersFull = row.getLong("wal_buffers_full"),
        ),
        instant(row, "stats_reset"),
      )
    (read.map((stats, _) => stats), read.flatMap((_, resetAt) => resetAt))

  /** Postgres 17 moved these counters out of `pg_stat_bgwriter` into their own view *and* reset
    * them in doing so. Both shapes are read so that a campaign on 15 or 16 still answers
    * 07-wal-tuning.md's "чекпоинты по времени или по объёму", and [[SutCheckpointerStats.view]]
    * records which one answered.
    */
  private def checkpointer(connection: Connection, version: PostgresVersion): (Option[SutCheckpointerStats], Option[Instant]) =
    val read =
      if version.hasStatCheckpointer then
        val totals = if version.hasCheckpointerTotals then ", num_done, slru_written" else ""
        one(
          connection,
          s"SELECT num_timed, num_requested, write_time, sync_time, buffers_written, stats_reset$totals FROM pg_stat_checkpointer",
        ): row =>
          (
            SutCheckpointerStats(
              view = "pg_stat_checkpointer",
              timed = row.getLong("num_timed"),
              requested = row.getLong("num_requested"),
              done = Option.when(version.hasCheckpointerTotals)(row.getLong("num_done")),
              writeTimeMillis = row.getDouble("write_time"),
              syncTimeMillis = row.getDouble("sync_time"),
              buffersWritten = row.getLong("buffers_written"),
              slruWritten = Option.when(version.hasCheckpointerTotals)(row.getLong("slru_written")),
            ),
            instant(row, "stats_reset"),
          )
      else
        one(
          connection,
          """SELECT checkpoints_timed, checkpoints_req, checkpoint_write_time, checkpoint_sync_time,
            |       buffers_checkpoint, stats_reset
            |FROM pg_stat_bgwriter""".stripMargin,
        ): row =>
          (
            SutCheckpointerStats(
              view = "pg_stat_bgwriter",
              timed = row.getLong("checkpoints_timed"),
              requested = row.getLong("checkpoints_req"),
              done = None,
              writeTimeMillis = row.getDouble("checkpoint_write_time"),
              syncTimeMillis = row.getDouble("checkpoint_sync_time"),
              buffersWritten = row.getLong("buffers_checkpoint"),
              slruWritten = None,
            ),
            instant(row, "stats_reset"),
          )
    (read.map((stats, _) => stats), read.flatMap((_, resetAt) => resetAt))

  /** Summed over every `backend_type` and `context`, because the question is how much I/O the
    * WAL cost the cluster and not which backend paid for it. Postgres 18 is where the WAL write
    * and fsync timings live now, so on 16 and 17 this view is read for the counts and the timings
    * it does carry, and 18 adds the byte volumes.
    */
  private def walIo(connection: Connection, version: PostgresVersion): (Option[SutWalIoStats], Option[Instant]) =
    val bytes = if version.hasStatIoBytes then ", coalesce(sum(read_bytes), 0) AS read_bytes, coalesce(sum(write_bytes), 0) AS write_bytes" else ""
    val read = one(
      connection,
      s"""SELECT coalesce(sum(reads), 0) AS reads, coalesce(sum(writes), 0) AS writes,
         |       coalesce(sum(fsyncs), 0) AS fsyncs, coalesce(sum(read_time), 0) AS read_time,
         |       coalesce(sum(write_time), 0) AS write_time, coalesce(sum(fsync_time), 0) AS fsync_time,
         |       max(stats_reset) AS stats_reset$bytes
         |FROM pg_stat_io
         |WHERE object = 'wal'""".stripMargin,
    ): row =>
      (
        SutWalIoStats(
          reads = row.getLong("reads"),
          writes = row.getLong("writes"),
          fsyncs = row.getLong("fsyncs"),
          readTimeMillis = row.getDouble("read_time"),
          writeTimeMillis = row.getDouble("write_time"),
          fsyncTimeMillis = row.getDouble("fsync_time"),
          readBytes = Option.when(version.hasStatIoBytes)(row.getLong("read_bytes")),
          writeBytes = Option.when(version.hasStatIoBytes)(row.getLong("write_bytes")),
        ),
        instant(row, "stats_reset"),
      )
    (read.map((stats, _) => stats), read.flatMap((_, resetAt) => resetAt))

  private def databaseActivity(connection: Connection): (SutDatabaseActivity, Option[Instant]) =
    one(
      connection,
      """SELECT xact_commit, xact_rollback, blks_read, blks_hit, tup_returned, tup_fetched,
        |       tup_inserted, tup_updated, tup_deleted, conflicts, temp_files, temp_bytes,
        |       deadlocks, blk_read_time, blk_write_time, stats_reset
        |FROM pg_stat_database
        |WHERE datname = current_database()""".stripMargin,
    ): row =>
      (
        SutDatabaseActivity(
          xactCommit = row.getLong("xact_commit"),
          xactRollback = row.getLong("xact_rollback"),
          blocksRead = row.getLong("blks_read"),
          blocksHit = row.getLong("blks_hit"),
          tuplesReturned = row.getLong("tup_returned"),
          tuplesFetched = row.getLong("tup_fetched"),
          tuplesInserted = row.getLong("tup_inserted"),
          tuplesUpdated = row.getLong("tup_updated"),
          tuplesDeleted = row.getLong("tup_deleted"),
          conflicts = row.getLong("conflicts"),
          tempFiles = row.getLong("temp_files"),
          tempBytes = row.getLong("temp_bytes"),
          deadlocks = row.getLong("deadlocks"),
          blockReadTimeMillis = row.getDouble("blk_read_time"),
          blockWriteTimeMillis = row.getDouble("blk_write_time"),
        ),
        instant(row, "stats_reset"),
      )
    .getOrElse(throw IllegalStateException("pg_stat_database has no row for the connected database"))

  private def tables(connection: Connection, version: PostgresVersion): List[SutTableStats] =
    val vacuumTime = if version.hasVacuumTimes then ", total_autovacuum_time" else ""
    all(
      connection,
      s"""SELECT schemaname, relname, seq_scan, idx_scan, n_tup_ins, n_tup_upd, n_tup_hot_upd,
         |       n_tup_del, n_live_tup, n_dead_tup, vacuum_count, autovacuum_count$vacuumTime
         |FROM pg_stat_user_tables
         |ORDER BY schemaname, relname""".stripMargin,
    ): row =>
      SutTableStats(
        schema = row.getString("schemaname"),
        table = row.getString("relname"),
        sequentialScans = row.getLong("seq_scan"),
        indexScans = optionalLong(row, "idx_scan"),
        rowsInserted = row.getLong("n_tup_ins"),
        rowsUpdated = row.getLong("n_tup_upd"),
        rowsHotUpdated = row.getLong("n_tup_hot_upd"),
        rowsDeleted = row.getLong("n_tup_del"),
        liveRows = row.getLong("n_live_tup"),
        deadRows = row.getLong("n_dead_tup"),
        vacuums = row.getLong("vacuum_count"),
        autovacuums = row.getLong("autovacuum_count"),
        autovacuumTimeMillis = Option.when(version.hasVacuumTimes)(row.getDouble("total_autovacuum_time")),
      )

  /** `None` when the extension is not installed in this database, which is the ordinary case
    * until somebody has put `pg_stat_statements` in `shared_preload_libraries` and restarted the
    * cluster (07-wal-tuning.md, "Включить измерения"). Asked of `pg_extension` rather than
    * discovered by letting the `SELECT` fail: a failed statement aborts nothing here, but it does
    * log as an error on the SUT, once per capture, for an absence that is expected.
    *
    * `pg_extension` alone cannot tell the other way this section is absent, though: `CREATE
    * EXTENSION pg_stat_statements` can succeed in a database whose cluster has never had the
    * library added to `shared_preload_libraries` (that one needs a restart, the `CREATE
    * EXTENSION` does not), and querying the view then raises rather than returning zero rows --
    * "pg_stat_statements must be loaded via shared_preload_libraries". That exception is caught
    * here and degrades this one section to `None` for the same reason the absence check exists at
    * all: it must not take the rest of this reading down with it.
    */
  private def statements(connection: Connection): Option[(List[SutStatementStats], Option[Instant])] =
    val installed = one(connection, "SELECT 1 AS present FROM pg_extension WHERE extname = 'pg_stat_statements'")(_ =>
      true,
    ).getOrElse(false)
    if !installed then None
    else
      try
        Some((statementRows(connection), statementsResetAt(connection)))
      catch case _: SQLException => None

  /** Every statement `pg_stat_statements` is currently tracking, not the top [[statementLimit]]
    * by cumulative time -- that cut is applied once, by [[SutStatsDelta]], after differencing the
    * two boundaries, and not here, before it. Applying it here would independently rank each
    * boundary by its own lifetime total: a statement already running long before the campaign,
    * ranked just under the cut in the "before" reading and pushed over it in the "after" one by
    * the campaign's own load, would then have no "before" row to pair with, and its *entire
    * lifetime total* -- not the campaign's share of it -- would stand as the delta. Capturing
    * every tracked statement at both boundaries is what gives every statement that could ever
    * enter the top [[statementLimit]] *of the delta* an accurate baseline to be differenced
    * against.
    *
    * Bounded by `pg_stat_statements.max` rather than left as a bare `SELECT *`, so a row count
    * this reader cannot exceed is read as the reader's own ceiling and not discovered by
    * `LIMIT`-driven guesswork. `current_setting` on it can only fail the way the rest of this
    * section already does -- not preloaded -- so it shares this method's `SQLException` handling
    * rather than needing its own.
    */
  private def statementRows(connection: Connection): List[SutStatementStats] =
    val capacity =
      one(connection, "SELECT current_setting('pg_stat_statements.max')::int AS max")(_.getInt("max"))
        .getOrElse(statementLimit)
    all(
      connection,
      s"""SELECT queryid, left(query, $queryTextLimit) AS query, calls, total_exec_time, rows,
         |       wal_records, wal_fpi, wal_bytes
         |FROM pg_stat_statements
         |WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
         |ORDER BY total_exec_time DESC
         |LIMIT $capacity""".stripMargin,
    ): row =>
      SutStatementStats(
        queryId = optionalLong(row, "queryid"),
        query = Option(row.getString("query")).getOrElse(""),
        calls = row.getLong("calls"),
        totalExecTimeMillis = row.getDouble("total_exec_time"),
        rows = row.getLong("rows"),
        walRecords = row.getLong("wal_records"),
        walFullPageImages = row.getLong("wal_fpi"),
        walBytes = row.getLong("wal_bytes"),
      )

  /** `pg_stat_statements_info` (extension 1.9+, bundled from Postgres 14) has one row, carrying
    * `pg_stat_statements_reset()`'s last instant the same way `pg_stat_wal.stats_reset` carries
    * `pg_stat_reset_shared('wal')`'s.
    */
  private def statementsResetAt(connection: Connection): Option[Instant] =
    one(connection, "SELECT stats_reset FROM pg_stat_statements_info")(row => instant(row, "stats_reset")).flatten

  private def gauges(connection: Connection): SutGauges =
    one(
      connection,
      """SELECT pg_database_size(current_database()) AS size_bytes,
        |       (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()) AS backends,
        |       current_setting('max_connections')::int AS max_connections""".stripMargin,
    ): row =>
      SutGauges(
        databaseSizeBytes = row.getLong("size_bytes"),
        backends = row.getLong("backends"),
        maxConnections = row.getInt("max_connections"),
      )
    .getOrElse(throw IllegalStateException("the SUT database did not report its size"))

  private def one[A](connection: Connection, sql: String)(read: ResultSet => A): Option[A] =
    all(connection, sql)(read).headOption

  private def all[A](connection: Connection, sql: String)(read: ResultSet => A): List[A] =
    val statement = connection.prepareStatement(sql)
    try
      statement.setQueryTimeout(queryTimeoutSeconds)
      val rows = statement.executeQuery()
      try
        val builder = List.newBuilder[A]
        while rows.next() do builder += read(rows)
        builder.result()
      finally rows.close()
    finally statement.close()

  private def optionalLong(row: ResultSet, column: String): Option[Long] =
    val value = row.getLong(column)
    Option.unless(row.wasNull())(value)

  private def instant(row: ResultSet, column: String): Option[Instant] =
    Option(row.getTimestamp(column)).map((timestamp: Timestamp) => timestamp.toInstant)

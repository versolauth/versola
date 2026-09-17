package versola.loadgen.sut

import zio.json.JsonCodec

/** One `pg_stat_*` reading of one system-under-test database, as the coordinator takes it at a
  * campaign boundary (runbook 05-report-spec.md §3, 07-wal-tuning.md "Что мерить").
  *
  * Split into [[SutCounters]] and [[SutGauges]] because the two halves are read the same way and
  * *compared* differently: a counter is cumulative since the last `pg_stat_reset` and only means
  * something as a difference between two captures, while a gauge is the value at the instant of
  * capture and subtracting two of them produces a number with no name. Every statistic 07 asks
  * for is a counter; `pg_database_size` and the backend count are the gauges the report shows
  * before and after.
  */
case class SutStats(counters: SutCounters, gauges: SutGauges) derives JsonCodec

/** The cumulative half. Every field is "since the server's statistics were last reset", so
  * [[SutStatsDelta]] subtracts a pair of them and is void if a reset happened in between --
  * which is why the reset instants are columns of the snapshot row rather than fields here.
  *
  * The `Option`s are Postgres major versions, not missing data: [[PostgresVersion]] decides which
  * views this server has, and a section absent there is a statistic this cluster cannot be asked
  * for at all. `None` says so; a zero would read as "measured, and nothing happened".
  */
case class SutCounters(
    wal: Option[SutWalStats],
    checkpointer: Option[SutCheckpointerStats],
    walIo: Option[SutWalIoStats],
    database: SutDatabaseActivity,
    tables: List[SutTableStats],
    statements: Option[List[SutStatementStats]],
) derives JsonCodec

/** The instantaneous half: what the database looked like at the moment of capture.
  *
  * `maxConnections` is configuration rather than a measurement, and it is here because §3 asks
  * for active connections *against* it -- the two are only readable together, and a report that
  * carried the numerator alone would need the operator to remember the denominator.
  */
case class SutGauges(databaseSizeBytes: Long, backends: Long, maxConnections: Int) derives JsonCodec

/** `pg_stat_wal`: the four figures 07-wal-tuning.md's WAL cycle is judged on.
  *
  * `fullPageImages` is a count of images, not their size: `wal_compression` moves `bytes` and
  * leaves this untouched, and reading the pair the other way round is how a run concludes that
  * compression did nothing. Postgres 18 removed `wal_write`/`wal_sync`/`wal_write_time`/
  * `wal_sync_time` from this view -- they are in [[SutWalIoStats]] -- so they are deliberately
  * not read here on any version.
  */
case class SutWalStats(records: Long, fullPageImages: Long, bytes: Long, buffersFull: Long) derives JsonCodec

/** Checkpoint activity: `timed` against `requested` is the one number that says whether
  * `max_wal_size` is large enough for the run's WAL rate (07-wal-tuning.md, "Шаг 2").
  *
  * @param view
  *   which view the reading came from -- `pg_stat_checkpointer` from Postgres 17, and
  *   `pg_stat_bgwriter` before it, where the same two counters are `checkpoints_timed` and
  *   `checkpoints_req`. Carried into the report because the two are not the same instrument:
  *   the 17 split also reset the counters, so a campaign spanning an upgrade must not have its
  *   before and after silently differenced.
  * @param done
  *   `num_done`, and `slruWritten`, exist only from Postgres 18.
  */
case class SutCheckpointerStats(
    view: String,
    timed: Long,
    requested: Long,
    done: Option[Long],
    writeTimeMillis: Double,
    syncTimeMillis: Double,
    buffersWritten: Long,
    slruWritten: Option[Long],
) derives JsonCodec

/** `pg_stat_io` restricted to `object = 'wal'`, summed over every backend type and context
  * (Postgres 16 and later).
  *
  * The times are zero unless `track_io_timing`/`track_wal_io_timing` are on, which
  * 07-wal-tuning.md's "Включить измерения" turns on for the campaign; they are read regardless,
  * because a zero here against a non-zero `writes` is itself the answer to "was the instrument
  * switched on".
  *
  * @param readBytes
  *   `read_bytes`/`write_bytes` replaced `op_bytes` in Postgres 18. The removed column is not
  *   read on 16/17 rather than being translated: it is a block size, not a volume, and
  *   multiplying it by the operation count here would put a derived figure in a table of
  *   measured ones.
  */
case class SutWalIoStats(
    reads: Long,
    writes: Long,
    fsyncs: Long,
    readTimeMillis: Double,
    writeTimeMillis: Double,
    fsyncTimeMillis: Double,
    readBytes: Option[Long],
    writeBytes: Option[Long],
) derives JsonCodec

/** `pg_stat_database` for the connected database: §3's TPS, cache hit ratio and deadlocks. */
case class SutDatabaseActivity(
    xactCommit: Long,
    xactRollback: Long,
    blocksRead: Long,
    blocksHit: Long,
    tuplesReturned: Long,
    tuplesFetched: Long,
    tuplesInserted: Long,
    tuplesUpdated: Long,
    tuplesDeleted: Long,
    conflicts: Long,
    tempFiles: Long,
    tempBytes: Long,
    deadlocks: Long,
    blockReadTimeMillis: Double,
    blockWriteTimeMillis: Double,
) derives JsonCodec

/** One row of `pg_stat_user_tables`.
  *
  * `rowsUpdated` against `rowsHotUpdated` is the HOT fraction of 07-wal-tuning.md's `fillfactor`
  * section, and `rowsInserted` against `rowsDeleted` on `refresh_tokens` is the question
  * 03-postgres-topology.md leaves open -- whether `PostgresCleanupManager`'s sweep keeps up with
  * the insert rate, or the table grows monotonically whatever the token TTL says.
  *
  * `liveRows`/`deadRows` are the planner's estimates, so they are gauges among counters: in a
  * [[SutStatsDelta]] they carry the reading taken *after* the run rather than a difference. They
  * are on this row anyway because they are only meaningful next to the churn that produced them.
  */
case class SutTableStats(
    schema: String,
    table: String,
    sequentialScans: Long,
    indexScans: Option[Long],
    rowsInserted: Long,
    rowsUpdated: Long,
    rowsHotUpdated: Long,
    rowsDeleted: Long,
    liveRows: Long,
    deadRows: Long,
    vacuums: Long,
    autovacuums: Long,
    /** `total_autovacuum_time`, new in Postgres 18. */
    autovacuumTimeMillis: Option[Double],
) derives JsonCodec

/** One row of `pg_stat_statements`, present only when the extension is installed in the database
  * being captured -- it needs `shared_preload_libraries`, which is a cluster restart, so a
  * campaign can perfectly well run without it and §3's "top-5 queries" is then the one row of the
  * report that is absent rather than wrong.
  *
  * @param queryId
  *   the identity the before and after snapshots are matched on. Nullable in Postgres when
  *   `compute_query_id` is off, and a statement without one cannot be differenced at all.
  * @param query
  *   the normalised text, truncated by [[SutStatsReader.queryTextLimit]]. Carried on both
  *   snapshots because a queryid is not a thing anyone can read.
  */
case class SutStatementStats(
    queryId: Option[Long],
    query: String,
    calls: Long,
    totalExecTimeMillis: Double,
    rows: Long,
    walRecords: Long,
    walFullPageImages: Long,
    walBytes: Long,
) derives JsonCodec

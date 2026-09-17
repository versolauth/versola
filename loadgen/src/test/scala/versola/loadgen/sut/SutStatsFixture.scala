package versola.loadgen.sut

import versola.loadgen.store.{SutStatPhase, SutStatSnapshotRow}

import java.time.Instant

/** A `pg_stat_*` reading with every section filled, so a spec can vary the one figure it is about
  * and still exercise the whole difference.
  *
  * Written as one readable snapshot with a scale factor rather than a builder per section: every
  * counter here is `base` times a fixed multiplier, which makes an expected delta something a
  * reader can compute in their head from the two scales a test passes in.
  */
object SutStatsFixture:

  def stats(base: Long, sizeBytes: Long): SutStats =
    SutStats(
      counters = SutCounters(
        wal = Some(
          SutWalStats(records = base * 10L, fullPageImages = base * 2L, bytes = base * 1024L, buffersFull = base),
        ),
        checkpointer = Some(
          SutCheckpointerStats(
            view = "pg_stat_checkpointer",
            timed = base,
            requested = base * 3L,
            done = Some(base * 4L),
            writeTimeMillis = base.toDouble,
            syncTimeMillis = base.toDouble * 2.0,
            buffersWritten = base * 5L,
            slruWritten = Some(base * 6L),
          ),
        ),
        walIo = Some(
          SutWalIoStats(
            reads = base,
            writes = base * 2L,
            fsyncs = base * 3L,
            readTimeMillis = base.toDouble,
            writeTimeMillis = base.toDouble * 2.0,
            fsyncTimeMillis = base.toDouble * 3.0,
            readBytes = Some(base * 8192L),
            writeBytes = Some(base * 16384L),
          ),
        ),
        database = activity(base),
        tables = List(table("public", "refresh_tokens", base)),
        statements = Some(List(statement(queryId = Some(4242L), base = base))),
      ),
      gauges = SutGauges(databaseSizeBytes = sizeBytes, backends = 7L, maxConnections = 200),
    )

  def activity(base: Long): SutDatabaseActivity =
    SutDatabaseActivity(
      xactCommit = base * 100L,
      xactRollback = base,
      blocksRead = base * 20L,
      blocksHit = base * 200L,
      tuplesReturned = base * 300L,
      tuplesFetched = base * 150L,
      tuplesInserted = base * 30L,
      tuplesUpdated = base * 40L,
      tuplesDeleted = base * 10L,
      conflicts = 0L,
      tempFiles = base,
      tempBytes = base * 4096L,
      deadlocks = 0L,
      blockReadTimeMillis = base.toDouble,
      blockWriteTimeMillis = base.toDouble * 2.0,
    )

  def table(schema: String, name: String, base: Long): SutTableStats =
    SutTableStats(
      schema = schema,
      table = name,
      sequentialScans = base,
      indexScans = Some(base * 50L),
      rowsInserted = base * 30L,
      rowsUpdated = base * 40L,
      rowsHotUpdated = base * 10L,
      rowsDeleted = base * 20L,
      liveRows = base * 1000L,
      deadRows = base * 100L,
      vacuums = base,
      autovacuums = base * 2L,
      autovacuumTimeMillis = Some(base.toDouble * 5.0),
    )

  def statement(queryId: Option[Long], base: Long): SutStatementStats =
    SutStatementStats(
      queryId = queryId,
      query = "INSERT INTO refresh_tokens ...",
      calls = base * 10L,
      totalExecTimeMillis = base.toDouble * 100.0,
      rows = base * 10L,
      walRecords = base * 20L,
      walFullPageImages = base * 2L,
      walBytes = base * 2048L,
    )

  def row(
      campaign: String,
      database: String,
      phase: SutStatPhase,
      capturedAt: Instant,
      statsResetAt: Option[Instant],
      statistics: SutStats,
  ): SutStatSnapshotRow =
    SutStatSnapshotRow(
      campaign = campaign,
      database = database,
      phase = phase,
      capturedAt = capturedAt,
      serverVersionNum = 180_000,
      statsResetAt = statsResetAt,
      walStatsResetAt = statsResetAt,
      statistics = statistics,
    )

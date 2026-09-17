package versola.loadgen.sut

import versola.loadgen.store.{SutStatPhase, SutStatSnapshotRow}
import zio.json.JsonCodec

/** What one SUT database did over one campaign: the difference between the two `pg_stat_*`
  * readings bracketing the run (runbook 05-report-spec.md §3, 07-wal-tuning.md).
  *
  * @param counters
  *   the difference itself, or `None` when the pair may not be differenced -- see
  *   [[countersReset]]. Its `Option` sections keep the meaning they have in [[SutCounters]]: a
  *   view this Postgres major does not have.
  * @param countersReset
  *   `pg_stat_reset`, `pg_stat_reset_shared('wal')` or a server restart happened between the two
  *   captures, so the "after" reading counts from the reset and not from the campaign's start.
  *   07-wal-tuning.md resets the counters before every step of its cycle, which makes this the
  *   expected shape of the accident: a difference taken across a reset is not slightly wrong, it
  *   is the second reading wearing the name of a delta. The gauges survive it -- they were never
  *   cumulative -- so the section still carries the run's disk growth.
  * @param before
  *   the gauges as they were at the start, against [[after]] at the end. `pg_database_size` on
  *   both is §3's "рост диска за прогон".
  */
case class SutStatsDelta(
    database: String,
    serverVersionNum: Int,
    beforeEpochMillis: Long,
    afterEpochMillis: Long,
    countersReset: Boolean,
    counters: Option[SutCounters],
    before: SutGauges,
    after: SutGauges,
) derives JsonCodec

object SutStatsDelta:

  /** Every database that has both of its boundaries recorded, in database order.
    *
    * A database with only one is left out rather than reported half-measured: one cumulative
    * reading says how much work the server has done since somebody last reset it, which is not a
    * statement about this campaign and would be read as one.
    */
  def from(rows: Iterable[SutStatSnapshotRow]): List[SutStatsDelta] =
    rows
      .groupBy(_.database)
      .toList
      .sortBy((database, _) => database)
      .flatMap: (_, ofDatabase) =>
        for
          before <- ofDatabase.find(_.phase == SutStatPhase.Before)
          after <- ofDatabase.find(_.phase == SutStatPhase.After)
        yield between(before, after)

  def between(before: SutStatSnapshotRow, after: SutStatSnapshotRow): SutStatsDelta =
    // A differing major is a restart with a different binary, which resets every counter in the
    // cluster; the reset instants alone would not always show it, since a restarted server that
    // has never had its statistics reset reports no reset instant on either side.
    val reset =
      before.statsResetAt != after.statsResetAt ||
        before.walStatsResetAt != after.walStatsResetAt ||
        before.serverVersionNum != after.serverVersionNum
    SutStatsDelta(
      database = after.database,
      serverVersionNum = after.serverVersionNum,
      beforeEpochMillis = before.capturedAt.toEpochMilli,
      afterEpochMillis = after.capturedAt.toEpochMilli,
      countersReset = reset,
      counters = Option.unless(reset)(counters(before.statistics.counters, after.statistics.counters)),
      before = before.statistics.gauges,
      after = after.statistics.gauges,
    )

  private def counters(before: SutCounters, after: SutCounters): SutCounters =
    SutCounters(
      // A section the "after" reading does not have is a section there is no difference of, even
      // if the "before" one had it: the pair was taken against one server, so this only arises
      // when a capture failed halfway, and inventing the missing end would invent the delta.
      wal = paired(before.wal, after.wal)(wal),
      checkpointer = paired(before.checkpointer, after.checkpointer)(checkpointer),
      walIo = paired(before.walIo, after.walIo)(walIo),
      database = database(before.database, after.database),
      tables = tables(before.tables, after.tables),
      statements = after.statements.map(statements(before.statements.getOrElse(Nil), _)),
    )

  private def paired[A](before: Option[A], after: Option[A])(difference: (A, A) => A): Option[A] =
    for
      start <- before
      end <- after
    yield difference(start, end)

  private def subtract(before: Option[Long], after: Option[Long]): Option[Long] =
    paired(before, after)((start, end) => end - start)

  private def subtractTime(before: Option[Double], after: Option[Double]): Option[Double] =
    paired(before, after)((start, end) => end - start)

  private def wal(before: SutWalStats, after: SutWalStats): SutWalStats =
    SutWalStats(
      records = after.records - before.records,
      fullPageImages = after.fullPageImages - before.fullPageImages,
      bytes = after.bytes - before.bytes,
      buffersFull = after.buffersFull - before.buffersFull,
    )

  private def checkpointer(before: SutCheckpointerStats, after: SutCheckpointerStats): SutCheckpointerStats =
    SutCheckpointerStats(
      view = after.view,
      timed = after.timed - before.timed,
      requested = after.requested - before.requested,
      done = subtract(before.done, after.done),
      writeTimeMillis = after.writeTimeMillis - before.writeTimeMillis,
      syncTimeMillis = after.syncTimeMillis - before.syncTimeMillis,
      buffersWritten = after.buffersWritten - before.buffersWritten,
      slruWritten = subtract(before.slruWritten, after.slruWritten),
    )

  private def walIo(before: SutWalIoStats, after: SutWalIoStats): SutWalIoStats =
    SutWalIoStats(
      reads = after.reads - before.reads,
      writes = after.writes - before.writes,
      fsyncs = after.fsyncs - before.fsyncs,
      readTimeMillis = after.readTimeMillis - before.readTimeMillis,
      writeTimeMillis = after.writeTimeMillis - before.writeTimeMillis,
      fsyncTimeMillis = after.fsyncTimeMillis - before.fsyncTimeMillis,
      readBytes = subtract(before.readBytes, after.readBytes),
      writeBytes = subtract(before.writeBytes, after.writeBytes),
    )

  private def database(before: SutDatabaseActivity, after: SutDatabaseActivity): SutDatabaseActivity =
    SutDatabaseActivity(
      xactCommit = after.xactCommit - before.xactCommit,
      xactRollback = after.xactRollback - before.xactRollback,
      blocksRead = after.blocksRead - before.blocksRead,
      blocksHit = after.blocksHit - before.blocksHit,
      tuplesReturned = after.tuplesReturned - before.tuplesReturned,
      tuplesFetched = after.tuplesFetched - before.tuplesFetched,
      tuplesInserted = after.tuplesInserted - before.tuplesInserted,
      tuplesUpdated = after.tuplesUpdated - before.tuplesUpdated,
      tuplesDeleted = after.tuplesDeleted - before.tuplesDeleted,
      conflicts = after.conflicts - before.conflicts,
      tempFiles = after.tempFiles - before.tempFiles,
      tempBytes = after.tempBytes - before.tempBytes,
      deadlocks = after.deadlocks - before.deadlocks,
      blockReadTimeMillis = after.blockReadTimeMillis - before.blockReadTimeMillis,
      blockWriteTimeMillis = after.blockWriteTimeMillis - before.blockWriteTimeMillis,
    )

  /** Matched on `(schema, table)`, and driven by the "after" reading: a table created during the
    * campaign has no "before" row and its whole churn belongs to the run, while a table dropped
    * during one has no churn left to attribute.
    *
    * `liveRows`/`deadRows` are carried through from the "after" reading rather than differenced,
    * as [[SutTableStats]] says -- the question `n_dead_tup` answers is how much bloat the run
    * left behind, not how much more of it there is than there was.
    */
  private def tables(before: List[SutTableStats], after: List[SutTableStats]): List[SutTableStats] =
    val start = before.map(table => (table.schema, table.table) -> table).toMap
    after.map: end =>
      start.get((end.schema, end.table)) match
        case None => end
        case Some(from) =>
          end.copy(
            sequentialScans = end.sequentialScans - from.sequentialScans,
            indexScans = subtract(from.indexScans, end.indexScans),
            rowsInserted = end.rowsInserted - from.rowsInserted,
            rowsUpdated = end.rowsUpdated - from.rowsUpdated,
            rowsHotUpdated = end.rowsHotUpdated - from.rowsHotUpdated,
            rowsDeleted = end.rowsDeleted - from.rowsDeleted,
            vacuums = end.vacuums - from.vacuums,
            autovacuums = end.autovacuums - from.autovacuums,
            autovacuumTimeMillis = subtractTime(from.autovacuumTimeMillis, end.autovacuumTimeMillis),
          )

  /** Matched on `queryid`, ordered by the time the run itself spent, not by the cumulative time
    * the "after" reading is sorted on.
    *
    * A statement absent from the "before" reading counts from zero. Both reasons it can be absent
    * say the same thing about the run: it was first executed during the campaign, or it was
    * outside the top the reader keeps and has since climbed into it -- either way what it did
    * before the run is small enough to be under the cut, and dropping the row instead would hide
    * exactly the statement the campaign made expensive. A statement with no `queryid` -- Postgres
    * with `compute_query_id` off -- has no identity to match on and is carried as its own
    * cumulative total, which is the honest reading of a row that cannot be differenced.
    */
  private def statements(before: List[SutStatementStats], after: List[SutStatementStats]): List[SutStatementStats] =
    val start = before.flatMap(statement => statement.queryId.map(_ -> statement)).toMap
    after
      .map: end =>
        end.queryId.flatMap(start.get) match
          case None => end
          case Some(from) =>
            end.copy(
              calls = end.calls - from.calls,
              totalExecTimeMillis = end.totalExecTimeMillis - from.totalExecTimeMillis,
              rows = end.rows - from.rows,
              walRecords = end.walRecords - from.walRecords,
              walFullPageImages = end.walFullPageImages - from.walFullPageImages,
              walBytes = end.walBytes - from.walBytes,
            )
      .sortBy(statement => -statement.totalExecTimeMillis)

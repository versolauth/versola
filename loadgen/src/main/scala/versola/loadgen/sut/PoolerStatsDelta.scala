package versola.loadgen.sut

import versola.loadgen.store.{PoolerStatSnapshotRow, SutStatPhase}
import zio.json.JsonCodec

/** What one PgBouncer did over one campaign: the difference between the two admin console
  * readings bracketing the run (runbook 05-report-spec.md §4).
  *
  * @param countersRestarted
  *   the pooler's process restarted between the two captures, so the "after" reading counts from
  *   the restart. PgBouncer's totals are "since process start" and it exposes no start time and
  *   no reset instant, so unlike [[SutStatsDelta.countersReset]] this cannot be read off the
  *   server -- it is inferred from a counter having gone backwards, which a restart is the only
  *   thing that makes happen. The inference is one-sided and the gap is worth stating: a pooler
  *   restarted early in a long run climbs back past its old totals before the "after" capture and
  *   is then indistinguishable from one that never restarted, leaving a delta that undercounts by
  *   whatever preceded the restart. A pod restart is visible in the cluster, and that is where a
  *   run whose §4 looks too small should be checked.
  * @param before
  *   the queue at each boundary. Not a peak -- see [[PoolerPoolStats.maxWaitMicros]].
  */
case class PoolerStatsDelta(
    pooler: String,
    version: String,
    beforeEpochMillis: Long,
    afterEpochMillis: Long,
    countersRestarted: Boolean,
    counters: Option[PoolerCounters],
    before: PoolerGauges,
    after: PoolerGauges,
) derives JsonCodec

object PoolerStatsDelta:

  /** Every pooler that has both of its boundaries recorded, in pooler order, for
    * [[SutStatsDelta.from]]'s reason.
    */
  def from(rows: Iterable[PoolerStatSnapshotRow]): List[PoolerStatsDelta] =
    rows
      .groupBy(_.pooler)
      .toList
      .sortBy((pooler, _) => pooler)
      .flatMap: (_, ofPooler) =>
        for
          before <- ofPooler.find(_.phase == SutStatPhase.Before)
          after <- ofPooler.find(_.phase == SutStatPhase.After)
        yield between(before, after)

  def between(before: PoolerStatSnapshotRow, after: PoolerStatSnapshotRow): PoolerStatsDelta =
    val difference = databases(before.statistics.counters.databases, after.statistics.counters.databases)
    // A differing version string is a restart with a different binary, and therefore a restart,
    // whether or not the counters happened to climb back over their old values in between.
    val restarted = before.version != after.version || difference.exists(negative)
    PoolerStatsDelta(
      pooler = after.pooler,
      version = after.version,
      beforeEpochMillis = before.capturedAt.toEpochMilli,
      afterEpochMillis = after.capturedAt.toEpochMilli,
      countersRestarted = restarted,
      counters = Option.unless(restarted)(PoolerCounters(difference)),
      before = before.statistics.gauges,
      after = after.statistics.gauges,
    )

  /** Matched on the database name and driven by the "after" reading, as
    * [[SutStatsDelta.tables]] is: a database added to the pooler's configuration mid-campaign has
    * no "before" row and all of its traffic belongs to the run.
    */
  private def databases(before: List[PoolerDatabaseStats], after: List[PoolerDatabaseStats]): List[PoolerDatabaseStats] =
    val start = before.map(database => database.database -> database).toMap
    after.map: end =>
      start.get(end.database) match
        case None => end
        case Some(from) =>
          end.copy(
            xactCount = end.xactCount - from.xactCount,
            queryCount = end.queryCount - from.queryCount,
            serverAssignmentCount = subtract(from.serverAssignmentCount, end.serverAssignmentCount),
            receivedBytes = end.receivedBytes - from.receivedBytes,
            sentBytes = end.sentBytes - from.sentBytes,
            xactTimeMicros = end.xactTimeMicros - from.xactTimeMicros,
            queryTimeMicros = end.queryTimeMicros - from.queryTimeMicros,
            waitTimeMicros = end.waitTimeMicros - from.waitTimeMicros,
            clientParseCount = subtract(from.clientParseCount, end.clientParseCount),
            serverParseCount = subtract(from.serverParseCount, end.serverParseCount),
            bindCount = subtract(from.bindCount, end.bindCount),
            clientLoginCount = subtract(from.clientLoginCount, end.clientLoginCount),
          )

  /** Whether a differenced row went backwards anywhere, which is the evidence of a restart.
    *
    * Asked of the difference rather than of the two readings so that a database present only in
    * the "after" one -- whose whole total is its delta, and cannot be negative -- does not count
    * as evidence of anything.
    */
  private def negative(database: PoolerDatabaseStats): Boolean =
    database.xactCount < 0 || database.queryCount < 0 || database.receivedBytes < 0 ||
      database.sentBytes < 0 || database.xactTimeMicros < 0 || database.queryTimeMicros < 0 ||
      database.waitTimeMicros < 0 || database.serverAssignmentCount.exists(_ < 0) ||
      database.clientParseCount.exists(_ < 0) || database.serverParseCount.exists(_ < 0) ||
      database.bindCount.exists(_ < 0) || database.clientLoginCount.exists(_ < 0)

  private def subtract(before: Option[Long], after: Option[Long]): Option[Long] =
    for
      start <- before
      end <- after
    yield end - start

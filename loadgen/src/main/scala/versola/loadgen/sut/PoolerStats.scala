package versola.loadgen.sut

import zio.json.JsonCodec

/** One reading of one PgBouncer admin console, as the coordinator takes it at a campaign boundary
  * (runbook 05-report-spec.md §4).
  *
  * Split into [[PoolerCounters]] and [[PoolerGauges]] for [[SutStats]]'s reason, and the split
  * decides which half of §4 this can actually answer. `SHOW STATS` is cumulative since the
  * process started, so its difference over the run is a measurement of the run; `SHOW POOLS` is
  * the queue as it stands at the instant of the query, so two readings of it are two instants and
  * neither is the run's peak.
  *
  * 04-pgbouncer.md installs the pooler before campaign 1, so this exists to be read on every
  * campaign rather than only once the pooler becomes necessary at 5M.
  */
case class PoolerStats(counters: PoolerCounters, gauges: PoolerGauges) derives JsonCodec

/** `SHOW STATS`, one row per database the pooler fronts.
  *
  * The `Option` fields are PgBouncer versions, as [[SutCounters]]'s are Postgres majors, but they
  * are decided differently and [[PoolerStatsReader]] says why: a `SHOW` command takes no column
  * list, so the set of statistics is the build's to choose and this reads whichever of them the
  * result set turned out to carry.
  */
case class PoolerCounters(databases: List[PoolerDatabaseStats]) derives JsonCodec

/** The instantaneous half: `SHOW POOLS`, plus the two limits from `SHOW CONFIG` that the pool
  * occupancy in it only means something against.
  *
  * The limits are `Option` because `SHOW CONFIG` is refused to a `stats_users` login on older
  * builds -- a deployment that grants the coordinator statistics and not configuration is a
  * reasonable one, and it costs the denominators rather than the reading.
  */
case class PoolerGauges(
    pools: List[PoolerPoolStats],
    maxClientConnections: Option[Int],
    defaultPoolSize: Option[Int],
) derives JsonCodec

/** One `SHOW STATS` row: what the pooler carried for one database since it started.
  *
  * `waitTimeMicros` against `queryCount` is the one figure §4 demands and nothing else in the
  * report can produce -- "сколько миллисекунд ожидания в пуле попадает в p99 `/token`". It is the
  * total over the run divided by the queries of the run, so it is a mean and not the p99 itself;
  * the report states it as such, because the pooler exposes no wait histogram to take a quantile
  * of.
  *
  * The times are microseconds, as the admin console reports them, and are not converted here:
  * `total_xact_time` and `total_query_time` are sums over a ten-hour run at campaign rates and
  * overflow nothing as `Long` microseconds, while rounding them to millis at capture would throw
  * away the only precision a per-query mean has left.
  */
case class PoolerDatabaseStats(
    database: String,
    xactCount: Long,
    queryCount: Long,
    serverAssignmentCount: Option[Long],
    receivedBytes: Long,
    sentBytes: Long,
    xactTimeMicros: Long,
    queryTimeMicros: Long,
    waitTimeMicros: Long,
    /** The three prepared-statement counters, reported only in named prepared statement tracking
      * mode -- `max_prepared_statements` above zero, which is exactly the setting
      * 04-pgbouncer.md's "Prepared statements" section turns on to keep `prepareThreshold` working
      * through a transaction-mode pooler. A campaign that left it off reports `None` here, and
      * that absence is the evidence the setting was in fact off.
      */
    clientParseCount: Option[Long],
    serverParseCount: Option[Long],
    bindCount: Option[Long],
    clientLoginCount: Option[Long],
) derives JsonCodec

/** One `SHOW POOLS` row: one `(database, user)` pair's queue at the instant of capture.
  *
  * `maxWaitMicros` is `maxwait`/`maxwait_us` combined, and it is the oldest *currently waiting*
  * client's wait -- not a high-water mark. It falls back to zero the moment the queue drains, so
  * a boundary capture of a healthy pooler reads zero and a campaign's worst queue never appears
  * here at all. It is carried because a non-zero reading at the "after" boundary is real evidence
  * of a pooler still saturated when the run stopped; the run's actual wait is
  * [[PoolerDatabaseStats.waitTimeMicros]]'s difference, and 08-report-data-gaps.md's exporter
  * item is what would give the peak.
  */
case class PoolerPoolStats(
    database: String,
    user: String,
    clientsActive: Long,
    clientsWaiting: Long,
    serversActive: Long,
    serversIdle: Long,
    serversUsed: Long,
    serversTested: Long,
    serversLogin: Long,
    maxWaitMicros: Long,
    poolMode: String,
) derives JsonCodec

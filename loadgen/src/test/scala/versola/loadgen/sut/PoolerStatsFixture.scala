package versola.loadgen.sut

import versola.loadgen.store.{PoolerStatSnapshotRow, SutStatPhase}

import java.time.Instant

/** An admin console reading with every section filled, on [[SutStatsFixture]]'s scale-factor
  * pattern and for its reason.
  */
object PoolerStatsFixture:

  val version: String = "PgBouncer 1.24.1"

  def stats(base: Long, clientsWaiting: Long = 0L): PoolerStats =
    PoolerStats(
      counters = PoolerCounters(databases = List(database("auth", base))),
      gauges = PoolerGauges(
        pools = List(pool("auth", "versola", clientsWaiting)),
        maxClientConnections = Some(2000),
        defaultPoolSize = Some(60),
      ),
    )

  def database(name: String, base: Long): PoolerDatabaseStats =
    PoolerDatabaseStats(
      database = name,
      xactCount = base * 100L,
      queryCount = base * 300L,
      serverAssignmentCount = Some(base * 100L),
      receivedBytes = base * 4096L,
      sentBytes = base * 8192L,
      xactTimeMicros = base * 500L,
      queryTimeMicros = base * 400L,
      waitTimeMicros = base * 50L,
      clientParseCount = Some(base * 10L),
      serverParseCount = Some(base * 5L),
      bindCount = Some(base * 300L),
      clientLoginCount = Some(base),
    )

  def pool(database: String, user: String, clientsWaiting: Long): PoolerPoolStats =
    PoolerPoolStats(
      database = database,
      user = user,
      clientsActive = 40L,
      clientsWaiting = clientsWaiting,
      serversActive = 30L,
      serversIdle = 20L,
      serversUsed = 5L,
      serversTested = 0L,
      serversLogin = 1L,
      maxWaitMicros = clientsWaiting * 1000L,
      poolMode = "transaction",
    )

  def row(
      campaign: String,
      pooler: String,
      phase: SutStatPhase,
      capturedAt: Instant,
      statistics: PoolerStats,
      version: String = version,
  ): PoolerStatSnapshotRow =
    PoolerStatSnapshotRow(
      campaign = campaign,
      pooler = pooler,
      phase = phase,
      capturedAt = capturedAt,
      version = version,
      statistics = statistics,
    )

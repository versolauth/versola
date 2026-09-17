package versola.loadgen.sut

import versola.loadgen.store.SutStatPhase
import zio.json.*
import zio.test.*

import java.time.Instant

/** The arithmetic behind §4's pooler half, without a PgBouncer, for [[SutStatsDeltaSpec]]'s
  * reason.
  *
  * One thing here has no counterpart there and is the reason this spec exists separately: the
  * admin console publishes no reset instant, so a restart is inferred from a counter having gone
  * backwards. That inference decides whether a campaign gets a §4 at all, and its one-sidedness
  * is a property to pin rather than a detail.
  */
object PoolerStatsDeltaSpec extends ZIOSpecDefault:

  private val campaign = "c1-1m-calibration"

  private val started = Instant.parse("2026-09-15T08:00:00Z")

  private val ended = started.plusSeconds(36_000L)

  private def pair(
      before: PoolerStats,
      after: PoolerStats,
      beforeVersion: String = PoolerStatsFixture.version,
      afterVersion: String = PoolerStatsFixture.version,
  ): PoolerStatsDelta =
    PoolerStatsDelta.between(
      PoolerStatsFixture.row(campaign, "auth-pooler", SutStatPhase.Before, started, before, beforeVersion),
      PoolerStatsFixture.row(campaign, "auth-pooler", SutStatPhase.After, ended, after, afterVersion),
    )

  def spec = suite("PoolerStatsDelta")(
    suite("between")(
      test("differences the cumulative counters and keeps both readings of the queue") {
        val delta = pair(PoolerStatsFixture.stats(base = 2L), PoolerStatsFixture.stats(base = 5L, clientsWaiting = 9L))
        val auth = delta.counters.get.databases.head
        assertTrue(
          !delta.countersRestarted,
          auth.xactCount == 300L,
          auth.queryCount == 900L,
          auth.waitTimeMicros == 150L,
          auth.bindCount.contains(900L),
          // The gauges are carried, not subtracted: the queue at each boundary is two instants.
          delta.before.pools.head.clientsWaiting == 0L,
          delta.after.pools.head.clientsWaiting == 9L,
          delta.beforeEpochMillis == started.toEpochMilli,
          delta.afterEpochMillis == ended.toEpochMilli,
        )
      },
      test("the mean wait per query is recoverable from the difference, which is §4's one demand") {
        val delta = pair(PoolerStatsFixture.stats(base = 2L), PoolerStatsFixture.stats(base = 5L))
        val auth = delta.counters.get.databases.head
        // 150 microseconds of wait over 900 queries of the run.
        assertTrue(auth.waitTimeMicros.toDouble / auth.queryCount.toDouble == 1.0 / 6.0)
      },
      test("a counter going backwards is a restart, and voids the counters but not the gauges") {
        val delta = pair(PoolerStatsFixture.stats(base = 9L), PoolerStatsFixture.stats(base = 2L, clientsWaiting = 3L))
        assertTrue(
          delta.countersRestarted,
          delta.counters.isEmpty,
          delta.after.pools.head.clientsWaiting == 3L,
        )
      },
      test("a version that changed across the pair is a restart even when every counter rose") {
        val delta = pair(
          PoolerStatsFixture.stats(base = 2L),
          PoolerStatsFixture.stats(base = 5L),
          afterVersion = "PgBouncer 1.25.0",
        )
        assertTrue(delta.countersRestarted, delta.counters.isEmpty, delta.version == "PgBouncer 1.25.0")
      },
      test("a restart whose counters climbed back past the old totals is not detected") {
        // The gap [[PoolerStatsDelta.countersRestarted]] documents, pinned so that a later change
        // claiming to close it has a failing test to show for it. Nothing in the reading
        // distinguishes this from a pooler that never restarted, because PgBouncer publishes no
        // process start time.
        val delta = pair(PoolerStatsFixture.stats(base = 2L), PoolerStatsFixture.stats(base = 5L))
        assertTrue(!delta.countersRestarted)
      },
      test("a database the pooler only started fronting mid-run counts from zero") {
        val before = PoolerStats(
          counters = PoolerCounters(List(PoolerStatsFixture.database("auth", 2L))),
          gauges = PoolerStatsFixture.stats(2L).gauges,
        )
        val after = PoolerStats(
          counters = PoolerCounters(
            List(PoolerStatsFixture.database("auth", 5L), PoolerStatsFixture.database("central", 4L)),
          ),
          gauges = PoolerStatsFixture.stats(5L).gauges,
        )
        val databases = pair(before, after).counters.get.databases
        assertTrue(
          databases.map(_.database) == List("auth", "central"),
          databases.head.queryCount == 900L,
          // Its whole total, because none of it predates the campaign.
          databases(1).queryCount == 1200L,
        )
      },
      test("a build reporting no prepared-statement counters differences the rest and reports None") {
        val plain = (base: Long) =>
          PoolerStats(
            counters = PoolerCounters(
              List(
                PoolerStatsFixture
                  .database("auth", base)
                  .copy(clientParseCount = None, serverParseCount = None, bindCount = None),
              ),
            ),
            gauges = PoolerStatsFixture.stats(base).gauges,
          )
        val auth = pair(plain(2L), plain(5L)).counters.get.databases.head
        assertTrue(auth.queryCount == 900L, auth.bindCount.isEmpty, auth.clientParseCount.isEmpty)
      },
    ),
    suite("from")(
      test("pairs the two boundaries of every pooler and drops a half-captured one") {
        val rows = List(
          PoolerStatsFixture.row(campaign, "auth-pooler", SutStatPhase.Before, started, PoolerStatsFixture.stats(2L)),
          PoolerStatsFixture.row(campaign, "auth-pooler", SutStatPhase.After, ended, PoolerStatsFixture.stats(5L)),
          PoolerStatsFixture.row(campaign, "edge-pooler", SutStatPhase.Before, started, PoolerStatsFixture.stats(1L)),
        )
        assertTrue(PoolerStatsDelta.from(rows).map(_.pooler) == List("auth-pooler"))
      },
    ),
    test("round-trips through JSON, which is how it reaches the report") {
      val delta = pair(PoolerStatsFixture.stats(base = 2L), PoolerStatsFixture.stats(base = 5L))
      assertTrue(delta.toJson.fromJson[PoolerStatsDelta] == Right(delta))
    },
  )

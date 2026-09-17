package versola.loadgen.store

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.loadgen.sut.PoolerStatsFixture
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

final case class PoolerStatSnapshotEnv(repository: PoolerStatSnapshotRepository)

/** `vu_pooler_stat_snapshots` against a real Postgres, for
  * [[PostgresSutStatSnapshotRepositorySpec]]'s two reasons: the `JSONB` round trip of a whole
  * admin console reading, and V0006's `ON CONFLICT` target.
  */
object PostgresPoolerStatSnapshotRepositorySpec extends LoadgenPostgresSpec, DatabaseSpecBase[PoolerStatSnapshotEnv]:

  private val started = Instant.parse("2026-01-01T12:00:00Z")

  private val ended = started.plusSeconds(36_000L)

  private def before(pooler: String): PoolerStatSnapshotRow =
    PoolerStatsFixture.row(
      campaign = "nightly",
      pooler = pooler,
      phase = SutStatPhase.Before,
      capturedAt = started,
      statistics = PoolerStatsFixture.stats(base = 1L),
    )

  private def after(pooler: String): PoolerStatSnapshotRow =
    PoolerStatsFixture.row(
      campaign = "nightly",
      pooler = pooler,
      phase = SutStatPhase.After,
      capturedAt = ended,
      statistics = PoolerStatsFixture.stats(base = 9L, clientsWaiting = 4L),
    )

  override lazy val environment =
    ZLayer:
      ZIO.serviceWith[TransactorZIO](xa => PoolerStatSnapshotEnv(PostgresPoolerStatSnapshotRepository(xa)))

  override def beforeEach(env: PoolerStatSnapshotEnv) =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(sql"TRUNCATE TABLE vu_pooler_stat_snapshots".update.run()).unit

  override def testCases(env: PoolerStatSnapshotEnv) = List(
    test("append then loadCampaign round-trips the whole reading, counters and queue alike") {
      val snapshot = before("auth-pooler")
      for
        _ <- env.repository.append(snapshot)
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(
        found == Vector(snapshot),
        found.head.statistics.counters.databases == snapshot.statistics.counters.databases,
        found.head.statistics.gauges == snapshot.statistics.gauges,
        found.head.version == PoolerStatsFixture.version,
      )
    },
    // A build that tracks no prepared statements reports no such counter, and the absence is
    // evidence about the deployment -- it has to survive the payload rather than arrive as a zero.
    test("a counter this build does not report round-trips as absent, not as zero") {
      val plain = before("auth-pooler")
      val snapshot = plain.copy(statistics =
        plain.statistics.copy(counters =
          versola.loadgen.sut.PoolerCounters(
            plain.statistics.counters.databases.map(_.copy(clientParseCount = None, bindCount = None)),
          ),
        ),
      )
      for
        _ <- env.repository.append(snapshot)
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(
        found.head.statistics.counters.databases.head.clientParseCount.isEmpty,
        found.head.statistics.counters.databases.head.bindCount.isEmpty,
        found.head.statistics.counters.databases.head.queryCount == 300L,
      )
    },
    // `SHOW CONFIG` is refusable to a stats-only login, and the two denominators are then absent
    // rather than zero -- a pool occupancy against a `max_client_conn` of 0 would read as full.
    test("a reading taken without SHOW CONFIG round-trips with both limits absent") {
      val plain = before("auth-pooler")
      val snapshot = plain.copy(statistics =
        plain.statistics
          .copy(gauges = plain.statistics.gauges.copy(maxClientConnections = None, defaultPoolSize = None)),
      )
      for
        _ <- env.repository.append(snapshot)
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(
        found.head.statistics.gauges.maxClientConnections.isEmpty,
        found.head.statistics.gauges.defaultPoolSize.isEmpty,
        found.head.statistics.gauges.pools.head.poolMode == "transaction",
      )
    },
    test("re-capturing a boundary that already landed keeps the first reading") {
      val snapshot = before("auth-pooler")
      for
        _ <- env.repository.append(snapshot)
        _ <- env.repository.append(snapshot.copy(capturedAt = ended, statistics = PoolerStatsFixture.stats(9L)))
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.size == 1, found.head.capturedAt == started)
    },
    test("the two boundaries of one pooler are two rows, in phase order") {
      for
        _ <- env.repository.append(after("auth-pooler"))
        _ <- env.repository.append(before("auth-pooler"))
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.map(_.phase) == Vector(SutStatPhase.Before, SutStatPhase.After))
    },
    test("each pooler keeps its own pair") {
      for
        _ <- env.repository.append(before("edge-pooler"))
        _ <- env.repository.append(before("auth-pooler"))
        _ <- env.repository.append(after("auth-pooler"))
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.map(row => (row.pooler, row.phase)) == Vector(
        ("auth-pooler", SutStatPhase.Before),
        ("auth-pooler", SutStatPhase.After),
        ("edge-pooler", SutStatPhase.Before),
      ))
    },
    test("loadCampaign reads one campaign, not the run before it") {
      for
        _ <- env.repository.append(before("auth-pooler"))
        _ <- env.repository.append(before("auth-pooler").copy(campaign = "smoke"))
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.map(_.campaign) == Vector("nightly"))
    },
  )

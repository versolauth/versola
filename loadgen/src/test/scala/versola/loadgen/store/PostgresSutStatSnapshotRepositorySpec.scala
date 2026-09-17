package versola.loadgen.store

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.loadgen.sut.SutStatsFixture
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.Instant

final case class SutStatSnapshotEnv(repository: SutStatSnapshotRepository)

/** `vu_sut_stat_snapshots` against a real Postgres.
  *
  * Two properties live in the SQL rather than in the Scala, and neither has a fake that could
  * check it: the `JSONB` round trip of a whole `pg_stat_*` reading -- a codec that drops a field
  * loses a statistic silently, a campaign at a time -- and the `ON CONFLICT` target, which has to
  * name exactly the columns the identity index covers, or a re-capture leaves two "before" rows
  * and the report's numbers depend on which one it read first.
  */
object PostgresSutStatSnapshotRepositorySpec extends LoadgenPostgresSpec, DatabaseSpecBase[SutStatSnapshotEnv]:

  private val started = Instant.parse("2026-01-01T12:00:00Z")

  private val ended = started.plusSeconds(36_000L)

  private def before(database: String): SutStatSnapshotRow =
    SutStatsFixture.row(
      campaign = "nightly",
      database = database,
      phase = SutStatPhase.Before,
      capturedAt = started,
      statsResetAt = Some(started.minusSeconds(600L)),
      statistics = SutStatsFixture.stats(base = 1L, sizeBytes = 4096L),
    )

  private def after(database: String): SutStatSnapshotRow =
    SutStatsFixture
      .row(
        campaign = "nightly",
        database = database,
        phase = SutStatPhase.After,
        capturedAt = ended,
        statsResetAt = Some(started.minusSeconds(600L)),
        statistics = SutStatsFixture.stats(base = 9L, sizeBytes = 40_960L),
      )

  override lazy val environment =
    ZLayer:
      ZIO.serviceWith[TransactorZIO](xa => SutStatSnapshotEnv(PostgresSutStatSnapshotRepository(xa)))

  override def beforeEach(env: SutStatSnapshotEnv) =
    ZIO.serviceWithZIO[TransactorZIO]:
      _.connect(sql"TRUNCATE TABLE vu_sut_stat_snapshots".update.run()).unit

  override def testCases(env: SutStatSnapshotEnv) = List(
    test("append then loadCampaign round-trips the whole reading, every section of the payload included") {
      val snapshot = before("auth")
      for
        _ <- env.repository.append(snapshot)
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(
        found == Vector(snapshot),
        found.head.statistics.counters.wal == snapshot.statistics.counters.wal,
        found.head.statistics.counters.tables == snapshot.statistics.counters.tables,
        found.head.statistics.counters.statements == snapshot.statistics.counters.statements,
        found.head.statistics.gauges == snapshot.statistics.gauges,
      )
    },
    // A server whose statistics have never been reset reports no instant, and that is a state the
    // pairing reads: two NULLs are a pair that may be differenced.
    test("a reading with no reset instant round-trips as one, not as an epoch") {
      val snapshot = before("auth").copy(statsResetAt = None, walStatsResetAt = None)
      for
        _ <- env.repository.append(snapshot)
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.head.statsResetAt.isEmpty, found.head.walStatsResetAt.isEmpty)
    },
    // The three sections that reset independently of `pg_stat_database`/`pg_stat_wal` round-trip
    // through their own columns, not through the payload's JSONB -- see the migration's own
    // comment for why they have to be answerable without decoding it.
    test("the checkpointer, walIo and statements reset instants round-trip through their own columns") {
      val snapshot = before("auth").copy(
        checkpointerStatsResetAt = Some(started.minusSeconds(120L)),
        walIoStatsResetAt = Some(started.minusSeconds(180L)),
        statementsStatsResetAt = Some(started.minusSeconds(240L)),
      )
      for
        _ <- env.repository.append(snapshot)
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(
        found.head.checkpointerStatsResetAt == snapshot.checkpointerStatsResetAt,
        found.head.walIoStatsResetAt == snapshot.walIoStatsResetAt,
        found.head.statementsStatsResetAt == snapshot.statementsStatsResetAt,
      )
    },
    test("re-capturing a boundary that already landed keeps the first reading") {
      val snapshot = before("auth")
      for
        _ <- env.repository.append(snapshot)
        _ <- env.repository.append(snapshot.copy(capturedAt = ended, statistics = SutStatsFixture.stats(9L, 40_960L)))
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.size == 1, found.head.capturedAt == started)
    },
    test("the two boundaries of one database are two rows, in phase order") {
      for
        _ <- env.repository.append(after("auth"))
        _ <- env.repository.append(before("auth"))
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.map(_.phase) == Vector(SutStatPhase.Before, SutStatPhase.After))
    },
    test("each database keeps its own pair") {
      for
        _ <- env.repository.append(before("central"))
        _ <- env.repository.append(before("auth"))
        _ <- env.repository.append(after("auth"))
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.map(row => (row.database, row.phase)) == Vector(
        ("auth", SutStatPhase.Before),
        ("auth", SutStatPhase.After),
        ("central", SutStatPhase.Before),
      ))
    },
    test("loadCampaign reads one campaign, not the run before it") {
      for
        _ <- env.repository.append(before("auth"))
        _ <- env.repository.append(before("auth").copy(campaign = "smoke"))
        found <- env.repository.loadCampaign("nightly")
      yield assertTrue(found.map(_.campaign) == Vector("nightly"))
    },
  )

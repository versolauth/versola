package versola.loadgen.sut

import versola.loadgen.store.SutStatPhase
import zio.json.*
import zio.test.*

import java.time.Instant

/** The arithmetic behind §3 of the report, without a database: what the run cost is a difference
  * of two cumulative readings, and every way that difference can be a lie has to be a case here
  * rather than something noticed in a campaign's numbers a week later.
  */
object SutStatsDeltaSpec extends ZIOSpecDefault:

  private val campaign = "c1-1m-calibration"

  private val started = Instant.parse("2026-09-15T08:00:00Z")

  private val ended = started.plusSeconds(36_000L)

  private val resetAt = Some(Instant.parse("2026-09-15T07:00:00Z"))

  private def pair(before: SutStats, after: SutStats): SutStatsDelta =
    SutStatsDelta.between(
      SutStatsFixture.row(campaign, "auth", SutStatPhase.Before, started, resetAt, before),
      SutStatsFixture.row(campaign, "auth", SutStatPhase.After, ended, resetAt, after),
    )

  def spec = suite("SutStatsDelta")(
    suite("between")(
      test("differences every counter and carries both readings of every gauge") {
        val delta = pair(SutStatsFixture.stats(base = 1L, sizeBytes = 100L), SutStatsFixture.stats(base = 4L, sizeBytes = 900L))
        val counters = delta.counters.get
        assertTrue(
          delta.database == "auth",
          delta.beforeEpochMillis == started.toEpochMilli,
          delta.afterEpochMillis == ended.toEpochMilli,
          !delta.countersReset,
          // Three scales of the fixture's base, since the reading before the run was one.
          counters.wal.map(_.bytes) == Some(3L * 1024L),
          counters.wal.map(_.fullPageImages) == Some(3L * 2L),
          counters.wal.map(_.buffersFull) == Some(3L),
          counters.checkpointer.map(_.requested) == Some(3L * 3L),
          counters.checkpointer.flatMap(_.slruWritten) == Some(3L * 6L),
          counters.walIo.flatMap(_.writeBytes) == Some(3L * 16384L),
          counters.database.xactCommit == 3L * 100L,
          // The HOT fraction of 07-wal-tuning.md is a ratio of two of these, so both have to be
          // the run's own churn and not the table's lifetime total.
          counters.tables.map(_.rowsUpdated) == List(3L * 40L),
          counters.tables.map(_.rowsHotUpdated) == List(3L * 10L),
          counters.statements.map(_.map(_.calls)) == Some(List(3L * 10L)),
          delta.before.databaseSizeBytes == 100L,
          delta.after.databaseSizeBytes == 900L,
        )
      },
      // n_live_tup and n_dead_tup are estimates of a state, not counts of events: the question
      // they answer is how much bloat the run left behind.
      test("carries the closing reading of the row estimates rather than differencing them") {
        val delta = pair(SutStatsFixture.stats(base = 1L, sizeBytes = 100L), SutStatsFixture.stats(base = 4L, sizeBytes = 900L))
        assertTrue(
          delta.counters.get.tables.map(_.liveRows) == List(4L * 1000L),
          delta.counters.get.tables.map(_.deadRows) == List(4L * 100L),
        )
      },
      test("refuses to difference a pair whose counters were reset between the two captures") {
        val delta = SutStatsDelta.between(
          SutStatsFixture.row(campaign, "auth", SutStatPhase.Before, started, resetAt, SutStatsFixture.stats(1L, 100L)),
          SutStatsFixture.row(
            campaign,
            "auth",
            SutStatPhase.After,
            ended,
            Some(started.plusSeconds(60L)),
            SutStatsFixture.stats(4L, 900L),
          ),
        )
        assertTrue(
          delta.countersReset,
          delta.counters.isEmpty,
          // The gauges were never cumulative, so the run's disk growth survives the reset.
          delta.before.databaseSizeBytes == 100L,
          delta.after.databaseSizeBytes == 900L,
        )
      },
      // A restarted server reports no reset instant on either side, so the version is the only
      // evidence left that the counters started again from zero.

      test("treats a changed server version as a reset") {
        val before = SutStatsFixture.row(campaign, "auth", SutStatPhase.Before, started, None, SutStatsFixture.stats(1L, 100L))
        val after = SutStatsFixture
          .row(campaign, "auth", SutStatPhase.After, ended, None, SutStatsFixture.stats(4L, 900L))
          .copy(serverVersionNum = 190_000)
        assertTrue(SutStatsDelta.between(before, after).counters.isEmpty)
      },
      // `pg_stat_reset_shared('io'/'checkpointer')` and `pg_stat_statements_reset()` each clear
      // one section without touching `pg_stat_database`/`pg_stat_wal`'s own reset instants, so a
      // reset detector that only compared those two would miss exactly this and difference a
      // "before" cumulative total against an "after" one that started counting from zero.
      test("treats a checkpointer-only, walIo-only or statements-only reset as a reset of the whole reading") {
        val laterReset = Some(started.plusSeconds(60L))
        val checkpointerReset = SutStatsDelta.between(
          SutStatsFixture.row(
            campaign,
            "auth",
            SutStatPhase.Before,
            started,
            resetAt,
            SutStatsFixture.stats(1L, 100L),
            checkpointerStatsResetAt = resetAt,
          ),
          SutStatsFixture.row(
            campaign,
            "auth",
            SutStatPhase.After,
            ended,
            resetAt,
            SutStatsFixture.stats(4L, 900L),
            checkpointerStatsResetAt = laterReset,
          ),
        )
        val walIoReset = SutStatsDelta.between(
          SutStatsFixture.row(
            campaign,
            "auth",
            SutStatPhase.Before,
            started,
            resetAt,
            SutStatsFixture.stats(1L, 100L),
            walIoStatsResetAt = resetAt,
          ),
          SutStatsFixture.row(
            campaign,
            "auth",
            SutStatPhase.After,
            ended,
            resetAt,
            SutStatsFixture.stats(4L, 900L),
            walIoStatsResetAt = laterReset,
          ),
        )
        val statementsReset = SutStatsDelta.between(
          SutStatsFixture.row(
            campaign,
            "auth",
            SutStatPhase.Before,
            started,
            resetAt,
            SutStatsFixture.stats(1L, 100L),
            statementsStatsResetAt = resetAt,
          ),
          SutStatsFixture.row(
            campaign,
            "auth",
            SutStatPhase.After,
            ended,
            resetAt,
            SutStatsFixture.stats(4L, 900L),
            statementsStatsResetAt = laterReset,
          ),
        )
        assertTrue(
          checkpointerReset.countersReset,
          checkpointerReset.counters.isEmpty,
          walIoReset.countersReset,
          walIoReset.counters.isEmpty,
          statementsReset.countersReset,
          statementsReset.counters.isEmpty,
        )
      },
      // A section that starts being captured between the two boundaries -- `pg_stat_statements`
      // created mid-campaign, with the extension already preloaded -- is not a reset: nothing
      // was cleared, the "before" reading simply could not see a section that did not exist yet.
      // `counters`'s own `paired`/`map` handling already reports that correctly as "no difference
      // of a section the before reading lacks" without this flagging the whole reading as reset.
      test("does not treat a reset instant appearing where the before reading had none as a reset") {
        val delta = SutStatsDelta.between(
          SutStatsFixture.row(campaign, "auth", SutStatPhase.Before, started, resetAt, SutStatsFixture.stats(1L, 100L)),
          SutStatsFixture.row(
            campaign,
            "auth",
            SutStatPhase.After,
            ended,
            resetAt,
            SutStatsFixture.stats(4L, 900L),
            statementsStatsResetAt = Some(started.plusSeconds(60L)),
          ),
        )
        assertTrue(!delta.countersReset, delta.counters.isDefined)
      },
      test("keeps a section absent on one major version absent in the difference") {
        val without = SutStatsFixture.stats(1L, 100L)
        val stripped = without.copy(counters = without.counters.copy(wal = None, walIo = None, statements = None))
        val delta = pair(stripped, stripped)
        assertTrue(
          delta.counters.get.wal.isEmpty,
          delta.counters.get.walIo.isEmpty,
          delta.counters.get.statements.isEmpty,
          delta.counters.get.checkpointer.isDefined,
        )
      },
      test("attributes the whole churn of a table that did not exist before the run") {
        val before = SutStatsFixture.stats(1L, 100L)
        val after = SutStatsFixture.stats(4L, 900L)
        val added = SutStatsFixture.table("public", "authorization_codes", base = 7L)
        val delta = pair(before, after.copy(counters = after.counters.copy(tables = after.counters.tables :+ added)))
        assertTrue(delta.counters.get.tables.find(_.table == "authorization_codes").map(_.rowsInserted) == Some(7L * 30L))
      },
      // A statement under the reader's cut before the run and above it after is exactly the
      // statement the campaign made expensive; dropping it would hide the finding.
      test("counts a statement first seen during the run from zero and ranks by the run's own time") {
        val before = SutStatsFixture.stats(1L, 100L)
        val after = SutStatsFixture.stats(2L, 900L)
        val newcomer = SutStatsFixture.statement(queryId = Some(99L), base = 5L)
        val delta = pair(
          before,
          after.copy(counters = after.counters.copy(statements = Some(after.counters.statements.get :+ newcomer))),
        )
        assertTrue(
          delta.counters.get.statements.get.map(_.queryId) == List(Some(99L), Some(4242L)),
          delta.counters.get.statements.get.map(_.calls) == List(5L * 10L, 1L * 10L),
        )
      },
      // `compute_query_id` off: there is no identity to match the two readings on, so the row
      // stands for what it is -- a cumulative total -- rather than being differenced against
      // whichever unnamed statement happened to sort next to it.
      test("carries a statement with no queryid through undifferenced") {
        val anonymous = SutStatsFixture.statement(queryId = None, base = 3L)
        val before = SutStatsFixture.stats(1L, 100L)
        val after = SutStatsFixture.stats(2L, 900L)
        val delta = pair(
          before.copy(counters = before.counters.copy(statements = Some(List(anonymous)))),
          after.copy(counters = after.counters.copy(statements = Some(List(anonymous)))),
        )
        assertTrue(delta.counters.get.statements.get.map(_.calls) == List(3L * 10L))
      },
      // The reader now captures every statement `pg_stat_statements` is tracking, not the top
      // statementLimit by lifetime total at each boundary independently -- precisely so this
      // ranking can be decided once, from the difference. Ranking each boundary on its own before
      // differencing would let a statement ranked just under that cut in "before" and pushed over
      // it in "after" by unrelated lifetime activity report that whole lifetime total, not the
      // run's share of it, as this campaign's delta.
      test("keeps only the top statementLimit statements by the run's own time, decided after differencing") {
        val ids = (1L to 25L).toList
        val before = ids.map(id => SutStatsFixture.statement(queryId = Some(id), base = 100L))
        // Every id's own delta is `id` milliseconds, despite an identical lifetime total before
        // the run -- so the ids ranked lowest by lifetime total and the ids ranked lowest by the
        // run's own time are different sets, and only the latter may be dropped.
        val after = ids.map(id =>
          SutStatsFixture.statement(queryId = Some(id), base = 100L).copy(totalExecTimeMillis = 100L * 100.0 + id.toDouble),
        )
        val beforeStats = SutStatsFixture.stats(1L, 100L)
        val afterStats = SutStatsFixture.stats(2L, 900L)
        val delta = pair(
          beforeStats.copy(counters = beforeStats.counters.copy(statements = Some(before))),
          afterStats.copy(counters = afterStats.counters.copy(statements = Some(after))),
        )
        val kept = delta.counters.get.statements.get
        assertTrue(
          kept.size == SutStatsReader.statementLimit,
          kept.map(_.queryId.get).toSet == (6L to 25L).toSet,
        )
      },
    ),
    suite("from")(
      test("pairs each database's two boundaries and orders them by name") {
        val rows = Vector(
          SutStatsFixture.row(campaign, "central", SutStatPhase.After, ended, resetAt, SutStatsFixture.stats(2L, 200L)),
          SutStatsFixture.row(campaign, "auth", SutStatPhase.Before, started, resetAt, SutStatsFixture.stats(1L, 100L)),
          SutStatsFixture.row(campaign, "central", SutStatPhase.Before, started, resetAt, SutStatsFixture.stats(1L, 100L)),
          SutStatsFixture.row(campaign, "auth", SutStatPhase.After, ended, resetAt, SutStatsFixture.stats(5L, 500L)),
        )
        val deltas = SutStatsDelta.from(rows)
        assertTrue(
          deltas.map(_.database) == List("auth", "central"),
          deltas.map(_.after.databaseSizeBytes) == List(500L, 200L),
        )
      },
      // One cumulative reading says how much work the server has done since somebody last reset
      // it, which is not a statement about this campaign and would be read as one.
      test("leaves out a database that has only one of its two boundaries") {
        val rows = Vector(
          SutStatsFixture.row(campaign, "auth", SutStatPhase.Before, started, resetAt, SutStatsFixture.stats(1L, 100L)),
          SutStatsFixture.row(campaign, "edge", SutStatPhase.Before, started, resetAt, SutStatsFixture.stats(1L, 100L)),
          SutStatsFixture.row(campaign, "auth", SutStatPhase.After, ended, resetAt, SutStatsFixture.stats(5L, 500L)),
        )
        assertTrue(SutStatsDelta.from(rows).map(_.database) == List("auth"))
      },
    ),
    // The snapshot is stored as one JSONB document, so a field this codec drops is a statistic
    // the report loses silently, a campaign at a time.
    test("a reading round-trips through its JSON encoding") {
      val stats = SutStatsFixture.stats(base = 3L, sizeBytes = 4096L)
      assertTrue(stats.toJson.fromJson[SutStats] == Right(stats))
    },
  )

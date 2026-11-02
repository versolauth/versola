package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object PostgresAntifraudEventRepositorySpec extends PostgresSpec:

  private case class Row(
      id: UUID,
      tenantId: Option[String],
      clientId: String,
      outcome: String,
      destPrefix: Option[String],
      userAgent: Option[String],
      decision: Option[String],
      score: Option[Int],
      rules: String,
      wouldAction: Option[String],
  ) derives DbCodec

  private val today = LocalDate.of(2026, 10, 21)
  private val farFuture = LocalDate.of(2099, 1, 1)

  private def event(at: Instant, outcome: AntifraudOutcome = AntifraudOutcome.sent) = AntifraudEvent(
    id = UUID.randomUUID(),
    at = at,
    tenantId = Some(TenantId("tenant-a")),
    clientId = ClientId("client-a"),
    conversationId = UUID.randomUUID(),
    action = AntifraudAction.otp_request,
    outcome = outcome,
    channel = AntifraudChannel.sms,
    subjectHash = "hash-1",
    destPrefix = Some("491512"),
    userAgent = Some("Mozilla/5.0"),
    score = None,
    rules = Nil,
  )

  private def partitions(xa: TransactorZIO): Task[List[String]] =
    xa.connect:
      sql"""SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'af_event' ORDER BY c.relname""".query[String].run().toList

  private def count(xa: TransactorZIO): Task[Long] =
    xa.connect(sql"SELECT COUNT(*) FROM af_event".query[Long].run().head)

  private def noPartitions: ZIO[TransactorZIO, Throwable, PostgresAntifraudEventRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      repository = PostgresAntifraudEventRepository(xa)
      // Everything older than a date far ahead goes, partitions this suite made included.
      _ <- repository.maintainPartitions(farFuture, 0, farFuture.plusYears(1))
    yield repository

  def spec = suite("PostgresAntifraudEventRepository")(
    test("creates a partition for each requested day, and again without error") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today, 3, today.minusDays(30))
        _ <- repository.maintainPartitions(today, 3, today.minusDays(30))
        names <- partitions(xa)
      yield assertTrue(names == List("af_event_20261021", "af_event_20261022", "af_event_20261023"))
    },
    test("writes a batch in one go and reads every column back") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today, 1, today.minusDays(30))
        first = event(today.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC))
        second = event(today.atTime(11, 0).toInstant(java.time.ZoneOffset.UTC), AntifraudOutcome.suppressed).copy(
          tenantId = None,
          destPrefix = None,
          userAgent = None,
          decision = Some("Allow"),
          score = Some(75),
          rules = List("prefix_budget", "velocity"),
          wouldAction = Some("Fake"),
        )
        _ <- repository.insertBatch(List(first, second))
        rows <- xa.connect:
          sql"SELECT id, tenant_id, client_id, outcome, dest_prefix, user_agent, decision, score, array_to_string(rules, ','), would_action FROM af_event ORDER BY at"
            .query[Row].run().toList
      yield assertTrue(
        rows.map(_.id) == List(first.id, second.id),
        rows.map(_.tenantId) == List(Some("tenant-a"), None),
        rows.map(_.outcome) == List("sent", "suppressed"),
        rows.map(_.destPrefix) == List(Some("491512"), None),
        rows.map(_.userAgent) == List(Some("Mozilla/5.0"), None),
        rows.map(_.decision) == List(None, Some("Allow")),
        // What the engine decided, which in shadow mode is not what was done.
        rows.map(_.wouldAction) == List(None, Some("Fake")),
        rows.map(_.score) == List(None, Some(75)),
        rows.map(_.rules) == List("", "prefix_budget,velocity"),
      )
    },
    test("deletes the rows older than the limit from the day it falls in, to the minute, and leaves the newer ones and other days") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today.minusDays(1), 3, today.minusDays(30))
        limit = today.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC)
        old = event(today.atTime(9, 59).toInstant(java.time.ZoneOffset.UTC))
        atLimit = event(limit)
        newer = event(today.atTime(10, 1).toInstant(java.time.ZoneOffset.UTC))
        // A row of the day before is a partition's to drop, not this method's.
        dayBefore = event(today.minusDays(1).atTime(23, 0).toInstant(java.time.ZoneOffset.UTC))
        _ <- repository.insertBatch(List(old, atLimit, newer, dayBefore))
        _ <- repository.pruneEvents(limit)
        left <- xa.connect(sql"SELECT id FROM af_event ORDER BY at".query[UUID].run().toList)
      yield assertTrue(left == List(dayBefore.id, atLimit.id, newer.id))
    },
    test("deletes in batches, so that no statement has a whole day to delete, and goes on until none is left") {
      for
        _ <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        repository = PostgresAntifraudEventRepository(xa, pruneBatch = 2)
        _ <- repository.maintainPartitions(today, 1, today.minusDays(30))
        limit = today.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC)
        old = (1 to 7).toList.map(i => event(today.atTime(9, i).toInstant(java.time.ZoneOffset.UTC)))
        newer = event(today.atTime(10, 30).toInstant(java.time.ZoneOffset.UTC))
        _ <- repository.insertBatch(newer :: old)
        _ <- repository.pruneEvents(limit)
        left <- xa.connect(sql"SELECT id FROM af_event".query[UUID].run().toList)
      yield assertTrue(left == List(newer.id))
    },
    test("an event of a day with no partition fails the write, so a late maintenance run is visible") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today, 1, today.minusDays(30))
        result <- repository.insertBatch(List(event(today.plusDays(5).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)))).exit
        rows <- count(xa)
      yield assertTrue(result.isFailure, rows == 0L)
    },
    test("drops the partitions of days older than the retention, and only those") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today.minusDays(3), 5, today.minusDays(30))
        _ <- repository.insertBatch(List(event(today.minusDays(3).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC))))
        _ <- repository.maintainPartitions(today, 1, today.minusDays(1))
        names <- partitions(xa)
        rows <- count(xa)
      yield assertTrue(
        names == List("af_event_20261020", "af_event_20261021", "af_event_20261022"),
        rows == 0L,
      )
    },
    test("leaves a table that is not one of its partitions alone") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(
          sql"CREATE TABLE af_event_20200101_old PARTITION OF af_event FOR VALUES FROM ('2020-01-01 00:00:00+00') TO ('2020-01-02 00:00:00+00')".update.run(),
        )
        _ <- repository.maintainPartitions(today, 1, today)
        names <- partitions(xa)
        _ <- xa.connect(sql"DROP TABLE af_event_20200101_old".update.run())
      yield assertTrue(names.contains("af_event_20200101_old"))
    },
    test("two maintenance runs at once both succeed") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        results <- ZIO.foreachPar(1 to 4)(_ => repository.maintainPartitions(today, 3, today.minusDays(30)).exit)
        names <- partitions(xa)
      yield assertTrue(results.forall(_.isSuccess), names.size == 3)
    },
    test("gives up on a lock it cannot get in time and leaves no partition behind, then succeeds once it is free") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        impatient = PostgresAntifraudEventRepository(xa, lockTimeout = 200.millis)
        // A reader that holds the table for a while, as a long statistics query would.
        reader <- xa.transact:
          sql"LOCK TABLE af_event IN ACCESS SHARE MODE".update.run()
          Thread.sleep(2000)
        .fork
        _ <- Live.live(ZIO.sleep(500.millis))
        blocked <- impatient.maintainPartitions(today, 3, today.minusDays(30)).exit
        during <- partitions(xa)
        _ <- reader.join
        _ <- impatient.maintainPartitions(today, 3, today.minusDays(30))
        after <- partitions(xa)
      yield assertTrue(blocked.isFailure, during.isEmpty, after.size == 3)
    },
    test("rolls the log up per tenant, range and minute, again without double counting, and prunes by age") {
      val minute = Instant.parse("2026-10-21T10:05:00Z")
      def at(second: Long, outcome: AntifraudOutcome, action: AntifraudAction = AntifraudAction.otp_request, prefix: String = "491512") =
        event(minute.plusSeconds(second), outcome).copy(action = action, destPrefix = Some(prefix))
      case class Stat(
          tenant: String,
          prefix: String,
          minute: Instant,
          requests: Int,
          sent: Int,
          throttled: Int,
          verified: Int,
          wrong: Int,
          fakeVerifies: Int,
      ) derives DbCodec
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(LocalDate.of(2026, 10, 21), 1, LocalDate.of(2026, 10, 1))
        _ <- xa.connect(sql"DELETE FROM af_dest_stats".update.run())
        _ <- repository.insertBatch(
          List(
            at(1, AntifraudOutcome.sent),
            at(2, AntifraudOutcome.sent, prefix = "491519"),
            at(3, AntifraudOutcome.throttled),
            at(4, AntifraudOutcome.verified, AntifraudAction.otp_verify),
            at(5, AntifraudOutcome.wrong_code, AntifraudAction.otp_verify),
            at(5, AntifraudOutcome.fake_verify, AntifraudAction.otp_verify),
            at(6, AntifraudOutcome.sent, prefix = "491612"),
            // the steps of a challenge are not requests of their own: the send that follows is the one counted
            at(8, AntifraudOutcome.challenged),
            at(9, AntifraudOutcome.challenge_failed),
            at(10, AntifraudOutcome.challenge_passed),
            // an address, and a minute outside the window
            event(minute.plusSeconds(7), AntifraudOutcome.sent).copy(channel = AntifraudChannel.email, destPrefix = None),
            at(70, AntifraudOutcome.sent),
          ),
        )
        ran <- repository.rollupStats(minute, minute.plusSeconds(60), prefixLength = 4, interval = Duration.Zero)
        again <- repository.rollupStats(minute, minute.plusSeconds(60), prefixLength = 4, interval = Duration.Zero)
        stats <- xa.connect(
          sql"""SELECT tenant_id, dest_prefix, minute, requests, sent, throttled, verified, wrong_codes, fake_verifies
                FROM af_dest_stats ORDER BY dest_prefix""".query[Stat].run().toList,
        )
        _ <- repository.pruneStats(minute.plusSeconds(60))
        left <- xa.connect(sql"SELECT COUNT(*) FROM af_dest_stats".query[Long].run().head)
      yield assertTrue(
        ran,
        again,
        stats == List(
          Stat("tenant-a", "4915", minute, requests = 3, sent = 2, throttled = 1, verified = 1, wrong = 1, fakeVerifies = 1),
          Stat("tenant-a", "4916", minute, requests = 1, sent = 1, throttled = 0, verified = 0, wrong = 0, fakeVerifies = 0),
        ),
        left == 0L,
      )
    },
    test("two rollups at once: one does the work, the other leaves it to it") {
      for
        repository <- noPartitions
        minute = Instant.parse("2026-10-21T10:05:00Z")
        results <- ZIO.foreachPar(1 to 6)(_ => repository.rollupStats(minute, minute.plusSeconds(60), 4, Duration.Zero).exit)
      yield assertTrue(results.forall(_.isSuccess))
    },
    test("the indexes nothing reads are gone from the table and its partitions, and the one the rollup reads is kept") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today, 2, today.minusDays(30))
        defs <- xa.connect(
          sql"SELECT indexdef FROM pg_indexes WHERE tablename LIKE 'af_event%' AND schemaname = current_schema()".query[String].run().toList,
        )
        // Nothing that reads the table breaks without them: the write, the rollup and a scan of the day for a phone.
        _ <- repository.insertBatch(List(event(today.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC))))
        ran <- repository.rollupStats(
          today.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC),
          today.atTime(10, 1).toInstant(java.time.ZoneOffset.UTC),
          4,
          Duration.Zero,
        )
        // What a preview of a phone does: a scan of the day for a subject.
        scanned <- xa.connect(
          sql"SELECT COUNT(*) FILTER (WHERE subject_hash = 'hash-1'), COUNT(*) FROM af_event WHERE at >= ${today.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)}"
            .query[(Long, Long)].run().head,
        )
      yield assertTrue(
        defs.nonEmpty,
        !defs.exists(d => d.contains("subject_hash") || d.contains("dest_prefix")),
        defs.exists(_.contains("USING brin (at)")),
        ran,
        scanned == (1L, 1L),
      )
    },
    test("the creates are one transaction and the drops another: a drop that fails leaves the partitions made ahead") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today.minusDays(5), 1, today.minusDays(30))
        // Anything that depends on the old partition makes its drop fail.
        _ <- xa.connect(sql"CREATE VIEW af_event_blocker AS SELECT id FROM af_event_20261016".update.run())
        blocked <- repository.maintainPartitions(today, 3, today).exit
        during <- partitions(xa)
        _ <- xa.connect(sql"DROP VIEW af_event_blocker".update.run())
        _ <- repository.maintainPartitions(today, 3, today)
        after <- partitions(xa)
      yield assertTrue(
        blocked.isFailure,
        during == List("af_event_20261016", "af_event_20261021", "af_event_20261022", "af_event_20261023"),
        after == List("af_event_20261021", "af_event_20261022", "af_event_20261023"),
      )
    },
    test("counts the days after today that have a partition") {
      for
        repository <- noPartitions
        none <- repository.partitionDaysAhead(today)
        _ <- repository.maintainPartitions(today, 8, today.minusDays(30))
        ahead <- repository.partitionDaysAhead(today)
        later <- repository.partitionDaysAhead(today.plusDays(5))
      yield assertTrue(none == 0, ahead == 7, later == 2)
    },
    test("a minute that did not change is not rewritten, and one that did is") {
      val minute = Instant.parse("2026-10-21T10:05:00Z")
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today, 1, today.minusDays(30))
        _ <- xa.connect(sql"DELETE FROM af_dest_stats".update.run())
        _ <- repository.insertBatch(List(event(minute.plusSeconds(1)), event(minute.plusSeconds(2), AntifraudOutcome.throttled)))
        _ <- repository.rollupStats(minute, minute.plusSeconds(60), 4, Duration.Zero)
        first <- xa.connect(sql"SELECT xmin::text FROM af_dest_stats".query[String].run().toList)
        _ <- repository.rollupStats(minute, minute.plusSeconds(60), 4, Duration.Zero)
        unchanged <- xa.connect(sql"SELECT xmin::text FROM af_dest_stats".query[String].run().toList)
        _ <- repository.insertBatch(List(event(minute.plusSeconds(3))))
        _ <- repository.rollupStats(minute, minute.plusSeconds(60), 4, Duration.Zero)
        changed <- xa.connect(sql"SELECT xmin::text, requests FROM af_dest_stats".query[(String, Int)].run().toList)
      yield assertTrue(first.size == 1, unchanged == first, changed.map(_._1) != first, changed.map(_._2) == List(3))
    },
    test("the rollup runs once per interval across instances: a run within half of one skips, and the marker is the database's") {
      val minute = Instant.parse("2026-10-21T10:05:00Z")
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(sql"DELETE FROM af_rollup_state".update.run())
        first <- repository.rollupStats(minute, minute.plusSeconds(60), 4, 1.minute)
        second <- repository.rollupStats(minute, minute.plusSeconds(60), 4, 1.minute)
        // Another instance with a shorter interval finds the marker old enough, and one with none at all always runs.
        other <- repository.rollupStats(minute, minute.plusSeconds(60), 4, 1.millis)
        again <- repository.rollupStats(minute, minute.plusSeconds(60), 4, Duration.Zero)
        // A marker that is a minute old is older than half of a one-minute interval.
        _ <- xa.connect(sql"UPDATE af_rollup_state SET last_run = clock_timestamp() - interval '40 seconds'".update.run())
        late <- repository.rollupStats(minute, minute.plusSeconds(60), 4, 1.minute)
      yield assertTrue(first, !second, other, again, late)
    },
    test("names and reads partitions by day") {
      assertTrue(
        PostgresAntifraudEventRepository.partitionName(today) == "af_event_20261021",
        PostgresAntifraudEventRepository.partitionDay("af_event_20261021") == Some(today),
        PostgresAntifraudEventRepository.partitionDay("af_event_20261321").isEmpty,
        PostgresAntifraudEventRepository.partitionDay("af_event_default").isEmpty,
        PostgresAntifraudEventRepository.partitionDay("conversations_20261021").isEmpty,
      )
    },
  ) @@ TestAspect.sequential

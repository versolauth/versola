package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.{Instant, ZoneOffset}
import java.util.UUID

object PostgresAntifraudEventRepositorySpec extends PostgresSpec, AntifraudEventRepositorySpec:

  import AntifraudEventRepositorySpec.*

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
      asn: Option[Int],
      asnClass: Option[String],
      country: Option[String],
  ) derives DbCodec

  private case class Stat(
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

  private class PostgresFixtures(xa: TransactorZIO) extends Fixtures:
    override def rows: Task[List[EventRow]] =
      xa.connect:
        sql"SELECT id, tenant_id, client_id, outcome, dest_prefix, user_agent, decision, score, array_to_string(rules, ','), would_action, asn, asn_class, country FROM af_event ORDER BY at"
          .query[Row].run().toList
      .map(_.map(r =>
        EventRow(
          id = r.id,
          tenantId = r.tenantId,
          clientId = r.clientId,
          outcome = r.outcome,
          destPrefix = r.destPrefix,
          userAgent = r.userAgent,
          decision = r.decision,
          score = r.score,
          rules = r.rules,
          wouldAction = r.wouldAction,
          asn = r.asn,
          asnClass = r.asnClass,
          country = r.country,
        ),
      ))

    override def countEvents: Task[Long] =
      xa.connect(sql"SELECT COUNT(*) FROM af_event".query[Long].run().head)

    override def stats: Task[List[StatRow]] =
      xa.connect:
        sql"""SELECT tenant_id, dest_prefix, minute, requests, sent, throttled, verified, wrong_codes, fake_verifies
              FROM af_dest_stats ORDER BY dest_prefix""".query[Stat].run().toList
      .map(_.map(s =>
        StatRow(
          tenant = s.tenant,
          prefix = s.prefix,
          minute = s.minute,
          requests = s.requests,
          sent = s.sent,
          throttled = s.throttled,
          verified = s.verified,
          wrong = s.wrong,
          fakeVerifies = s.fakeVerifies,
        ),
      ))

    override def clearStats: Task[Unit] =
      xa.connect(sql"DELETE FROM af_dest_stats".update.run()).unit

    override def clearRollupState: Task[Unit] =
      xa.connect(sql"DELETE FROM af_rollup_state".update.run()).unit

    override def ageRollupMarker(by: Duration): Task[Unit] =
      val age = SqlLiteral(s"${by.toMillis} * interval '1 millisecond'")
      xa.connect(sql"UPDATE af_rollup_state SET last_run = clock_timestamp() - $age".update.run()).unit

  private def partitions(xa: TransactorZIO): Task[List[String]] =
    xa.connect:
      sql"""SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'af_event' ORDER BY c.relname""".query[String].run().toList

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresAntifraudEventRepository(xa), PostgresFixtures(xa))

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      // Everything older than a date far ahead goes, partitions this suite made included.
      _ <- env.repository.maintainPartitions(from = farFuture, days = 0, keepFrom = farFuture.plusYears(1))
      _ <- xa.connect:
        sql"DELETE FROM af_dest_stats".update.run()
        sql"DELETE FROM af_rollup_state".update.run()
    yield ()

  override def implementationCases(env: Env) =
    val repository = env.repository
    List(
      test("a partition is a table named for its day") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          names <- partitions(xa)
        yield assertTrue(names == List("af_event_20261021", "af_event_20261022", "af_event_20261023"))
      },
      test("deletes in batches, so that no statement has a whole day to delete, and goes on until none is left") {
        for
          xa <- ZIO.service[TransactorZIO]
          batched = PostgresAntifraudEventRepository(xa, pruneBatch = 2)
          _ <- batched.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          limit = utc(today.atTime(10, 0))
          old = (1 to 7).toList.map(i => event(utc(today.atTime(9, i))))
          newer = event(utc(today.atTime(10, 30)))
          _ <- batched.insertBatch(newer :: old)
          _ <- batched.pruneEvents(limit)
          left <- env.fixtures.rows.map(_.map(_.id))
        yield assertTrue(left == List(newer.id))
      },
      test("leaves a table that is not one of its partitions alone") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(
            sql"CREATE TABLE af_event_20200101_old PARTITION OF af_event FOR VALUES FROM ('2020-01-01 00:00:00+00') TO ('2020-01-02 00:00:00+00')".update.run(),
          )
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today)
          names <- partitions(xa)
          _ <- xa.connect(sql"DROP TABLE af_event_20200101_old".update.run())
        yield assertTrue(names.contains("af_event_20200101_old"))
      },
      test("gives up on a lock it cannot get in time and leaves no partition behind, then succeeds once it is free") {
        for
          xa <- ZIO.service[TransactorZIO]
          impatient = PostgresAntifraudEventRepository(xa, lockTimeout = 200.millis)
          // A reader that holds the table for a while, as a long statistics query would.
          reader <- xa.transact:
            sql"LOCK TABLE af_event IN ACCESS SHARE MODE".update.run()
            Thread.sleep(2000)
          .fork
          _ <- Live.live(ZIO.sleep(500.millis))
          blocked <- impatient.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30)).exit
          during <- partitions(xa)
          _ <- reader.join
          _ <- impatient.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          after <- partitions(xa)
        yield assertTrue(blocked.isFailure, during.isEmpty, after.size == 3)
      },
      test("the indexes nothing reads are gone from the table and its partitions, and the one the rollup reads is kept") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today, days = 2, keepFrom = today.minusDays(30))
          defs <- xa.connect(
            sql"SELECT indexdef FROM pg_indexes WHERE tablename LIKE 'af_event%' AND schemaname = current_schema()".query[String].run().toList,
          )
          // Nothing that reads the table breaks without them: the write, the rollup and a scan of the day for a phone.
          _ <- repository.insertBatch(List(event(utc(today.atTime(10, 0)))))
          ran <- repository.rollupStats(
            from = utc(today.atTime(10, 0)),
            to = utc(today.atTime(10, 1)),
            prefixLength = 4,
            interval = Duration.Zero,
          )
          // What a preview of a phone does: a scan of the day for a subject.
          scanned <- xa.connect(
            sql"SELECT COUNT(*) FILTER (WHERE subject_hash = 'hash-1'), COUNT(*) FROM af_event WHERE at >= ${today.atStartOfDay().atOffset(ZoneOffset.UTC)}"
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
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today.minusDays(5), days = 1, keepFrom = today.minusDays(30))
          // Anything that depends on the old partition makes its drop fail.
          _ <- xa.connect(sql"CREATE VIEW af_event_blocker AS SELECT id FROM af_event_20261016".update.run())
          blocked <- repository.maintainPartitions(from = today, days = 3, keepFrom = today).exit
          during <- partitions(xa)
          _ <- xa.connect(sql"DROP VIEW af_event_blocker".update.run())
          _ <- repository.maintainPartitions(from = today, days = 3, keepFrom = today)
          after <- partitions(xa)
        yield assertTrue(
          blocked.isFailure,
          during == List("af_event_20261016", "af_event_20261021", "af_event_20261022", "af_event_20261023"),
          after == List("af_event_20261021", "af_event_20261022", "af_event_20261023"),
        )
      },
      test("a minute that did not change is not rewritten, and one that did is") {
        val minute = Instant.parse("2026-10-21T10:05:00Z")
        def rollup = repository.rollupStats(from = minute, to = minute.plusSeconds(60), prefixLength = 4, interval = Duration.Zero)
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          _ <- repository.insertBatch(List(event(minute.plusSeconds(1)), event(minute.plusSeconds(2), AntifraudOutcome.throttled)))
          _ <- rollup
          first <- xa.connect(sql"SELECT xmin::text FROM af_dest_stats".query[String].run().toList)
          _ <- rollup
          unchanged <- xa.connect(sql"SELECT xmin::text FROM af_dest_stats".query[String].run().toList)
          _ <- repository.insertBatch(List(event(minute.plusSeconds(3))))
          _ <- rollup
          changed <- xa.connect(sql"SELECT xmin::text, requests FROM af_dest_stats".query[(String, Int)].run().toList)
        yield assertTrue(first.size == 1, unchanged == first, changed.map(_._1) != first, changed.map(_._2) == List(3))
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
    )

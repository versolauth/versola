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

    override def countUnpartitioned: Task[Long] =
      xa.connect(sql"SELECT COUNT(*) FROM af_event_default".query[Long].run().head)

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

  /** The tables of the days that are partitions of the log, not the default partition. */
  private def partitions(xa: TransactorZIO): Task[List[String]] =
    xa.connect:
      sql"""SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'af_event' AND c.relname <> 'af_event_default'
            ORDER BY c.relname""".query[String].run().toList

  /** Every table named for a day, partition or not. */
  private def dayTables(xa: TransactorZIO): Task[List[String]] =
    xa.connect:
      sql"""SELECT relname FROM pg_class WHERE relkind = 'r' AND relname ~ '^af_event_[0-9]{8}' AND relnamespace = current_schema()::text::regnamespace
            ORDER BY relname""".query[String].run().toList

  /** A read, as a long report or a preview is: ACCESS SHARE on `table` and, for the parent, on every partition, until `hold` has passed. */
  private def reader(xa: TransactorZIO, table: String, hold: Duration) =
    val relation = SqlLiteral(table)
    xa.transact:
      sql"SELECT COUNT(*) FROM $relation".query[Long].run()
      Thread.sleep(hold.toMillis)

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
        sql"TRUNCATE af_event_default".update.run()
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
      test(
        "gives up on the lock it cannot get in time (a read holds the default partition) and leaves no partition behind, then succeeds once it is free",
      ) {
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
      test("the creates and the drops are separate: a drop that fails leaves the partitions made ahead, and is finished by the next run") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today.minusDays(5), days = 1, keepFrom = today.minusDays(30))
          // Anything that depends on the old table makes its drop fail (the detach before it does not mind).
          _ <- xa.connect(sql"CREATE VIEW af_event_blocker AS SELECT id FROM af_event_20261016".update.run())
          blocked <- repository.maintainPartitions(from = today, days = 3, keepFrom = today).exit
          during <- partitions(xa)
          leftover <- dayTables(xa)
          _ <- xa.connect(sql"DROP VIEW af_event_blocker".update.run())
          _ <- repository.maintainPartitions(from = today, days = 3, keepFrom = today)
          after <- partitions(xa)
          tables <- dayTables(xa)
        yield assertTrue(
          blocked.isFailure,
          during == List("af_event_20261021", "af_event_20261022", "af_event_20261023"),
          // Detached, so no partition and no longer read through af_event, but not yet dropped.
          leftover.contains("af_event_20261016"),
          after == List("af_event_20261021", "af_event_20261022", "af_event_20261023"),
          tables == after,
        )
      },
      test("the default partition is there, takes no day, and is never dropped") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today, days = 2, keepFrom = today.minusDays(30))
          _ <- repository.maintainPartitions(from = farFuture, days = 0, keepFrom = farFuture.plusYears(1))
          isPartition <- xa.connect(
            sql"""SELECT COUNT(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                  WHERE c.relname = 'af_event_default' AND i.inhparent = 'af_event'::regclass""".query[Long].run().head,
          )
          ahead <- repository.partitionDaysAhead(today.minusDays(1))
        yield assertTrue(isPartition == 1L, ahead == 0)
      },
      test("a day's table is attached without a scan, and leaves no constraint behind, and keeps the indexes of the parent") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          constraints <- xa.connect(
            sql"SELECT conname FROM pg_constraint WHERE conrelid = 'af_event_20261021'::regclass AND contype = 'c'".query[String].run().toList,
          )
          indexes <- xa.connect(
            sql"""SELECT COUNT(*), COUNT(*) FILTER (WHERE i.relispartition) FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
                  WHERE x.indrelid = 'af_event_20261021'::regclass""".query[(Long, Long)].run().head,
          )
        // The primary key and the BRIN index, each the parent's own and not a second one built by the attach.
        yield assertTrue(constraints.isEmpty, indexes == (2L, 2L))
      },
      test("attaching a day does not hold up the writer behind a long read, and gives up when the read has the default partition") {
        for
          xa <- ZIO.service[TransactorZIO]
          // Waits longer than the writer is tried for, so that the writer's insert falls into the wait.
          patient = PostgresAntifraudEventRepository(xa, lockTimeout = 2.seconds)
          _ <- patient.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          read <- reader(xa, "af_event", 3.seconds).fork
          _ <- Live.live(ZIO.sleep(500.millis))
          attach <- patient.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30)).exit.fork
          _ <- Live.live(ZIO.sleep(300.millis))
          started <- Live.live(Clock.nanoTime)
          _ <- patient.insertBatch(List(event(utc(today.atTime(10, 0)))))
          took <- Live.live(Clock.nanoTime).map(now => (now - started).nanos)
          result <- attach.join
          during <- partitions(xa)
          _ <- read.join
          _ <- patient.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          after <- partitions(xa)
        yield assertTrue(
          // The insert did not queue behind the attach, which was still waiting for the default partition's lock.
          took < 1.second,
          // PostgreSQL takes ACCESS EXCLUSIVE on the default partition to attach, and a read of af_event holds it.
          result.isFailure,
          during == List("af_event_20261021"),
          after.size == 3,
        )
      },
      test("an old day is detached under the short lock timeout: the writer is held up for that long at most, and the next run does it") {
        for
          xa <- ZIO.service[TransactorZIO]
          hasty = PostgresAntifraudEventRepository(xa, lockTimeout = 2.seconds, detachLockTimeout = 200.millis)
          _ <- repository.maintainPartitions(from = today.minusDays(5), days = 1, keepFrom = today.minusDays(30))
          old = event(utc(today.minusDays(5).atTime(9, 0)))
          _ <- repository.insertBatch(List(old))
          read <- reader(xa, "af_event", 3.seconds).fork
          _ <- Live.live(ZIO.sleep(500.millis))
          started <- Live.live(Clock.nanoTime)
          blocked <- hasty.maintainPartitions(from = today, days = 0, keepFrom = today).exit
          took <- Live.live(Clock.nanoTime).map(now => (now - started).nanos)
          during <- partitions(xa)
          rowsDuring <- env.fixtures.countEvents
          _ <- read.join
          _ <- hasty.maintainPartitions(from = today, days = 0, keepFrom = today)
          after <- dayTables(xa)
        yield assertTrue(
          blocked.isFailure,
          took < 1500.millis,
          // Nothing is half done: still a partition, with its row, until a run gets its lock.
          during == List("af_event_20261016"),
          rowsDuring == 1L,
          after.isEmpty,
        )
      },
      test("a day that was detached and not dropped (a run cut off between the two) is dropped by the next run") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- repository.maintainPartitions(from = today.minusDays(5), days = 2, keepFrom = today.minusDays(30))
          _ <- xa.connect(sql"ALTER TABLE af_event DETACH PARTITION af_event_20261016".update.run())
          cut <- dayTables(xa)
          cutPartitions <- partitions(xa)
          // A table of a day that is not old is not touched by the drops, and the creates attach it.
          _ <- xa.connect(sql"CREATE TABLE af_event_20261022 (LIKE af_event INCLUDING ALL)".update.run())
          moved <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(1))
          after <- dayTables(xa)
          afterPartitions <- partitions(xa)
          again <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(1))
        yield assertTrue(
          cut == List("af_event_20261016", "af_event_20261017"),
          cutPartitions == List("af_event_20261017"),
          moved == 0L,
          again == 0L,
          after == List("af_event_20261021", "af_event_20261022", "af_event_20261023"),
          afterPartitions == after,
        )
      },
      test("the drop of a table that is being read fails on the lock timeout and is done by the next run") {
        for
          xa <- ZIO.service[TransactorZIO]
          impatient = PostgresAntifraudEventRepository(xa, lockTimeout = 300.millis)
          _ <- repository.maintainPartitions(from = today.minusDays(5), days = 1, keepFrom = today.minusDays(30))
          // A run that detached it and was cut off before the drop; a read that started before the detach still holds it.
          _ <- xa.connect(sql"ALTER TABLE af_event DETACH PARTITION af_event_20261016".update.run())
          read <- reader(xa, "af_event_20261016", 2.seconds).fork
          _ <- Live.live(ZIO.sleep(500.millis))
          blocked <- impatient.maintainPartitions(from = today, days = 0, keepFrom = today).exit
          during <- dayTables(xa)
          _ <- read.join
          _ <- impatient.maintainPartitions(from = today, days = 0, keepFrom = today)
          after <- dayTables(xa)
        yield assertTrue(blocked.isFailure, during == List("af_event_20261016"), after.isEmpty)
      },
      test("a day's rows beyond a batch are moved in batches, and all of them, counted, are in its partition") {
        for
          xa <- ZIO.service[TransactorZIO]
          batched = PostgresAntifraudEventRepository(xa, pruneBatch = 2)
          _ <- batched.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          day = (1 to 7).toList.map(i => event(utc(today.plusDays(1).atTime(9, i))))
          other = event(utc(today.plusDays(2).atTime(9, 0)))
          _ <- batched.insertBatch(other :: day)
          moved <- batched.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          held <- env.fixtures.countUnpartitioned
          inPartition <- xa.connect(sql"SELECT COUNT(*) FROM af_event_20261022".query[Long].run().head)
          rows <- env.fixtures.rows.map(_.map(_.id).toSet)
        yield assertTrue(moved == 8L, held == 0L, inPartition == 7L, rows == (other :: day).map(_.id).toSet)
      },
      test("the owner of the table is read, with the role the service connects as") {
        val owner = "af_owner_test"
        for
          xa <- ZIO.service[TransactorZIO]
          own <- repository.ownership
          // The test role is a superuser and may alter anything, so only the name of the owner can be told apart here; whether a role
          // that does not own the table is refused is the unit spec's, with a stub, since that needs a connection of another role.
          changed <- (for
            _ <- xa.connect(sql"DROP ROLE IF EXISTS af_owner_test".update.run())
            _ <- xa.connect(sql"CREATE ROLE af_owner_test".update.run())
            _ <- xa.connect(sql"ALTER TABLE af_event OWNER TO af_owner_test".update.run())
            read <- repository.ownership
          yield read).ensuring(
            xa.connect:
              sql"ALTER TABLE af_event OWNER TO ${SqlLiteral(own.owner)}".update.run()
              sql"DROP ROLE IF EXISTS af_owner_test".update.run()
            .orDie,
          )
        yield assertTrue(
          own.role == own.owner,
          own.mayAlter,
          changed.owner == owner,
          changed.role == own.role,
          changed.mayAlter,
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

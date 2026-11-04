package versola.util.postgres

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.DailyPartitions.DefaultRows
import zio.*
import zio.test.*

import java.time.{LocalDate, ZoneOffset}
import java.util.UUID

/** The day-partition maintenance on throwaway tables of its own, so that it is held to what it promises whoever uses it: the audit
  * log's rows are never moved out of the default partition (an insert-only replication would see the same key twice), the
  * antifraud log's are, and the guard a table's delete trigger asks for is set wherever the maintenance deletes. */
object PostgresDailyPartitionsSpec extends PostgresSpec:

  private val today = LocalDate.parse("2026-11-04")

  private def at(day: LocalDate) = day.atTime(10, 0).atOffset(ZoneOffset.UTC)

  /** A partitioned table with a default partition, as `V0030` and the audit migration make them; `guarded` adds the audit log's
    * trigger, which refuses a delete unless the transaction says it is the maintenance's. Whatever an earlier run left under the
    * name (loose day tables included) goes first. */
  private def fresh(xa: TransactorZIO, name: String, guarded: Boolean): Task[Unit] =
    xa.connect:
      val table = SqlLiteral(name)
      val leftovers =
        sql"""SELECT relname FROM pg_class WHERE relkind = 'r' AND relname ~ ${s"^${name}_"} AND relnamespace = current_schema()::text::regnamespace"""
          .query[String].run().toList
      leftovers.foreach(left => sql"DROP TABLE IF EXISTS ${SqlLiteral(left)} CASCADE".update.run())
      sql"DROP TABLE IF EXISTS $table CASCADE".update.run()
      sql"""CREATE TABLE $table (id UUID NOT NULL, at TIMESTAMPTZ NOT NULL, PRIMARY KEY (id, at)) PARTITION BY RANGE (at)""".update.run()
      sql"CREATE TABLE ${SqlLiteral(s"${name}_default")} PARTITION OF $table DEFAULT".update.run()
      if guarded then
        sql"""
          CREATE OR REPLACE FUNCTION dp_probe_immutable() RETURNS trigger AS $$$$
          BEGIN
            IF current_setting('versola.dp_probe_maintenance', true) IS DISTINCT FROM 'on' THEN
              RAISE EXCEPTION 'append-only';
            END IF;
            RETURN OLD;
          END $$$$ LANGUAGE plpgsql
        """.update.run()
        sql"CREATE TRIGGER dp_probe_immutable BEFORE DELETE ON $table FOR EACH ROW EXECUTE FUNCTION dp_probe_immutable()".update.run()
      ()

  private def insert(xa: TransactorZIO, name: String, day: LocalDate): Task[Unit] =
    xa.connect(sql"INSERT INTO ${SqlLiteral(name)} (id, at) VALUES (${UUID.randomUUID()}, ${at(day)})".update.run()).unit

  private def count(xa: TransactorZIO, relation: String): Task[Long] =
    xa.connect(sql"SELECT COUNT(*) FROM ${SqlLiteral(relation)}".query[Long].run().head)

  private def days(xa: TransactorZIO, dp: DailyPartitions): Task[List[LocalDate]] = xa.connect(dp.partitionDays).map(_.sorted)

  private def partitions(table: String, rows: DefaultRows, deleteGuard: Option[String] = None, batch: Int = 5000) =
    DailyPartitions(table = table, defaultRows = rows, deleteGuard = deleteGuard, batch = batch, label = Some("probe"))

  val spec = suite("PostgresDailyPartitions")(
    suite("LeaveAndReport")(
      test("a day with a row in the default partition is not attached and is reported, and the row stays where it is") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_leave", guarded = false)
          dp = partitions("dp_leave", DefaultRows.LeaveAndReport)
          _ <- insert(xa, "dp_leave", today.plusDays(1))
          report <- dp.maintain(xa, from = today, days = 3, keepFrom = today.minusDays(30))
          attached <- days(xa, dp)
          inDefault <- count(xa, "dp_leave_default")
        yield assertTrue(
          report.daysLeftInDefault == List(today.plusDays(1)),
          report.movedFromDefault == 0L,
          report.created == 2,
          attached == List(today, today.plusDays(2)),
          inDefault == 1L,
        )
      },
      test("the day is reported on every run while its row is there, and no run moves the row") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_leave_again", guarded = false)
          dp = partitions("dp_leave_again", DefaultRows.LeaveAndReport)
          _ <- insert(xa, "dp_leave_again", today)
          first <- dp.maintain(xa, from = today, days = 2, keepFrom = today.minusDays(30))
          second <- dp.maintain(xa, from = today, days = 2, keepFrom = today.minusDays(30))
          inDefault <- count(xa, "dp_leave_again_default")
        yield assertTrue(
          first.daysLeftInDefault == List(today),
          second.daysLeftInDefault == List(today),
          second.created == 0,
          inDefault == 1L,
        )
      },
      test("after the row is pruned the day is attached by the next run, and the row is not in the partition") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_leave_pruned", guarded = false)
          dp = partitions("dp_leave_pruned", DefaultRows.LeaveAndReport)
          _ <- insert(xa, "dp_leave_pruned", today.plusDays(1))
          _ <- dp.maintain(xa, from = today, days = 3, keepFrom = today.minusDays(30))
          deleted <- dp.pruneDefault(xa, before = at(today.plusDays(2)).toInstant)
          report <- dp.maintain(xa, from = today, days = 3, keepFrom = today.minusDays(30))
          attached <- days(xa, dp)
          inPartition <- count(xa, "dp_leave_pruned_" + today.plusDays(1).toString.replace("-", ""))
        yield assertTrue(
          deleted == 1L,
          report.daysLeftInDefault.isEmpty,
          report.created == 1,
          attached == List(today, today.plusDays(1), today.plusDays(2)),
          inPartition == 0L,
        )
      },
      test("a row in the default partition of a day that is already attached elsewhere does not hold up the other days") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_leave_other", guarded = false)
          dp = partitions("dp_leave_other", DefaultRows.LeaveAndReport)
          _ <- insert(xa, "dp_leave_other", today.minusDays(3))
          report <- dp.maintain(xa, from = today, days = 3, keepFrom = today.minusDays(30))
          attached <- days(xa, dp)
        yield assertTrue(report.daysLeftInDefault.isEmpty, attached == List(today, today.plusDays(1), today.plusDays(2)))
      },
    ),
    suite("MoveOnAttach")(
      test("a day's rows in the default partition are moved into the day's partition as it is attached, and counted") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_move", guarded = false)
          dp = partitions("dp_move", DefaultRows.MoveOnAttach)
          _ <- insert(xa, "dp_move", today.plusDays(1))
          _ <- insert(xa, "dp_move", today.plusDays(1))
          report <- dp.maintain(xa, from = today, days = 3, keepFrom = today.minusDays(30))
          inDefault <- count(xa, "dp_move_default")
          inPartition <- count(xa, "dp_move_" + today.plusDays(1).toString.replace("-", ""))
        yield assertTrue(
          report.movedFromDefault == 2L,
          report.daysLeftInDefault.isEmpty,
          report.created == 3,
          inDefault == 0L,
          inPartition == 2L,
        )
      },
      test("more rows than a batch are moved in batches and all arrive") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_move_many", guarded = false)
          dp = partitions("dp_move_many", DefaultRows.MoveOnAttach, batch = 2)
          _ <- ZIO.foreachDiscard(1 to 5)(_ => insert(xa, "dp_move_many", today))
          report <- dp.maintain(xa, from = today, days = 1, keepFrom = today.minusDays(30))
          inDefault <- count(xa, "dp_move_many_default")
        yield assertTrue(report.movedFromDefault == 5L, inDefault == 0L)
      },
    ),
    suite("maintenance")(
      test("makes the partitions for the days asked, again without error, and names them for their day") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_basic", guarded = false)
          dp = partitions("dp_basic", DefaultRows.LeaveAndReport)
          first <- dp.maintain(xa, from = today, days = 3, keepFrom = today.minusDays(30))
          second <- dp.maintain(xa, from = today, days = 3, keepFrom = today.minusDays(30))
          attached <- days(xa, dp)
        yield assertTrue(
          first.created == 3,
          second.created == 0,
          attached == List(today, today.plusDays(1), today.plusDays(2)),
          dp.partitionName(today) == "dp_basic_20261104",
          dp.partitionDay("dp_basic_20261104") == Some(today),
          dp.partitionDay("dp_basic_default").isEmpty,
          dp.partitionDay("dp_basic_20261304").isEmpty,
          dp.partitionDay("other_20261104").isEmpty,
        )
      },
      test("drops the days before keepFrom, counts them, and leaves the default partition") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_drop", guarded = false)
          dp = partitions("dp_drop", DefaultRows.LeaveAndReport)
          _ <- dp.maintain(xa, from = today.minusDays(3), days = 5, keepFrom = today.minusDays(30))
          report <- dp.maintain(xa, from = today, days = 1, keepFrom = today)
          attached <- days(xa, dp)
          defaultIsPartition <- xa.connect(
            sql"SELECT COUNT(*) FROM pg_class WHERE relname = 'dp_drop_default' AND relispartition".query[Long].run().head,
          )
        yield assertTrue(report.dropped == 3, attached == List(today, today.plusDays(1)), defaultIsPartition == 1L)
      },
      test("a loose day table left by a run cut off between the detach and the drop is dropped by the next run") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_loose", guarded = false)
          dp = partitions("dp_loose", DefaultRows.LeaveAndReport)
          _ <- dp.maintain(xa, from = today.minusDays(2), days = 1, keepFrom = today.minusDays(30))
          _ <- xa.connect(sql"ALTER TABLE dp_loose DETACH PARTITION dp_loose_20261102".update.run())
          report <- dp.maintain(xa, from = today, days = 1, keepFrom = today)
          left <- xa.connect(sql"SELECT COUNT(*) FROM pg_class WHERE relname = 'dp_loose_20261102'".query[Long].run().head)
        yield assertTrue(report.dropped == 1, left == 0L)
      },
      test("counts the days ahead of a day that have a partition, and not the default partition") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_ahead", guarded = false)
          dp = partitions("dp_ahead", DefaultRows.LeaveAndReport)
          _ <- dp.maintain(xa, from = today, days = 4, keepFrom = today.minusDays(30))
          ahead <- dp.partitionDaysAhead(xa, today)
          aheadOfLast <- dp.partitionDaysAhead(xa, today.plusDays(3))
        yield assertTrue(ahead == 3, aheadOfLast == 0)
      },
      test("reads who owns the table and whether the service may alter it") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_owner", guarded = false)
          ownership <- partitions("dp_owner", DefaultRows.LeaveAndReport).ownership(xa)
        yield assertTrue(ownership.owner == "dev", ownership.role == "dev", ownership.mayAlter)
      },
      test("asking for the owner of a table that is not there fails, saying the migration may not have run") {
        for
          xa <- ZIO.service[TransactorZIO]
          error <- partitions("dp_missing_table", DefaultRows.LeaveAndReport).ownership(xa).flip
        yield assertTrue(error.getMessage.contains("has the migration run"))
      },
    ),
    suite("pruneDefault")(
      test("deletes the rows older than the limit from the default partition only, in batches, and counts them") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_prune", guarded = false)
          dp = partitions("dp_prune", DefaultRows.LeaveAndReport, batch = 2)
          _ <- dp.maintain(xa, from = today, days = 1, keepFrom = today.minusDays(30))
          _ <- ZIO.foreachDiscard(1 to 5)(_ => insert(xa, "dp_prune", today.minusDays(10)))
          _ <- insert(xa, "dp_prune", today.plusDays(5))
          _ <- insert(xa, "dp_prune", today)
          deleted <- dp.pruneDefault(xa, before = at(today.minusDays(7)).toInstant)
          inDefault <- count(xa, "dp_prune_default")
          inToday <- count(xa, "dp_prune_20261104")
        yield assertTrue(deleted == 5L, inDefault == 1L, inToday == 1L)
      },
      test("passes the table's delete trigger with the guard, and is refused by it without") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_guarded", guarded = true)
          _ <- insert(xa, "dp_guarded", today.minusDays(10))
          unguarded <- partitions("dp_guarded", DefaultRows.LeaveAndReport).pruneDefault(xa, at(today).toInstant).exit
          stillThere <- count(xa, "dp_guarded_default")
          deleted <- partitions("dp_guarded", DefaultRows.LeaveAndReport, deleteGuard = Some("versola.dp_probe_maintenance"))
            .pruneDefault(xa, at(today).toInstant)
          after <- count(xa, "dp_guarded_default")
        yield assertTrue(unguarded.isFailure, stillThere == 1L, deleted == 1L, after == 0L)
      },
      test("the guard does not stay on for the connection's next transaction") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- fresh(xa, "dp_guard_scope", guarded = true)
          dp = partitions("dp_guard_scope", DefaultRows.LeaveAndReport, deleteGuard = Some("versola.dp_probe_maintenance"))
          _ <- dp.pruneDefault(xa, at(today).toInstant)
          _ <- insert(xa, "dp_guard_scope", today.minusDays(10))
          later <- xa.transact(sql"DELETE FROM dp_guard_scope".update.run()).exit
        yield assertTrue(later.isFailure)
      },
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

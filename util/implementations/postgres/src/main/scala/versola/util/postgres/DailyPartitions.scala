package versola.util.postgres

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.partitions.{PartitionReport, TableOwnership}
import zio.{Duration, Exit, Task, ZIO, durationInt}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneOffset}

/** Day-range partitions of one table, maintained by every instance of a service: created ahead, old ones detached and dropped,
  * and the rows that landed in the default partition (the table's `${table}_default`, which never goes) handled as `defaultRows`
  * says. Extracted from the antifraud event log; the locking rationale is with each step below.
  *
  * @param table             the partitioned table; its partitions are `${table}_YYYYMMDD`
  * @param defaultRows       what a day's rows in the default partition mean when the day's partition is made
  * @param lockTimeout       how long a maintenance step waits for a lock that does not stall the writer: the other instances'
  *                          maintenance, autovacuum and other DDL, and (to attach a day) the default partition, which a read
  *                          of `table` holds
  * @param detachLockTimeout how long the detach of an old day waits for its locks. Detaching takes ACCESS EXCLUSIVE on `table`,
  *                          which the writer's inserts queue behind, so this is the longest the writer can be held up by one
  *                          attempt: short, and a failed attempt is retried by the next run
  * @param statementTimeout  the longest one statement of a prune or a move may take: a statement that hangs (a partition being
  *                          locked, a slow disk) would otherwise hold the caller while its queue fills
  * @param batch             rows deleted or moved per statement, each its own transaction under its own timeout
  * @param deleteGuard       a custom setting set to `on` for the transaction of every delete, for a table whose trigger refuses
  *                          deletes that are not the service's maintenance (the audit log's `versola.audit_maintenance`)
  * @param label             what the rows are called in metrics (`db_client_operation_duration_seconds`'s operation) and log lines
  */
class DailyPartitions(
    table: String,
    defaultRows: DailyPartitions.DefaultRows,
    lockTimeout: Duration = 5.seconds,
    detachLockTimeout: Duration = 500.millis,
    statementTimeout: Duration = 30.seconds,
    batch: Int = 5000,
    deleteGuard: Option[String] = None,
    label: Option[String] = None,
) extends BasicCodecs:
  import DailyPartitions.*

  private val name = label.getOrElse(table)
  private val relation = SqlLiteral(TableName.validated(table))
  private val defaultPartition = SqlLiteral(s"${table}_default")
  private val partitionPattern = raw"${table}_(\d{8})".r
  private val loosePattern = s"^${table}_[0-9]{8}"

  /** The table of a day, partition or not. */
  def partitionName(day: LocalDate): String = s"${table}_${suffix.format(day)}"

  /** The day a partition holds, `None` for a table that is not one of this table's partitions (the default partition included). */
  def partitionDay(name: String): Option[LocalDate] =
    name match
      case partitionPattern(digits) => scala.util.Try(LocalDate.parse(digits, suffix)).toOption
      case _ => None

  /** The CHECK that carries a day's bounds while its table is not yet a partition. */
  private def boundsName(day: LocalDate): String = s"${partitionName(day)}_day"

  private def timed()(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()

  /** Lets a delete through the table's trigger, where it has one. */
  private def guarded()(using DbCon): Unit =
    deleteGuard.foreach(guard => sql"SET LOCAL ${SqlLiteral(guard)} = 'on'".update.run())

  /** Makes sure there is a partition for each of the `days` days starting at `from` (UTC), creating the ones missing, and drops
    * the partitions that end before `keepFrom`. The creates are one phase and the drops another: a drop that cannot take its lock
    * (a long read on the partition of an old day, a dependent object) must not stop the partitions of the days ahead, or the
    * writer would run out of them after the few days it is ahead. The drops are tried even when the creates failed; the first
    * failure is the one reported. Safe to run from several instances at once. */
  def maintain(xa: TransactorZIO, from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
    for
      created <- createAhead(xa, from, days).exit
      dropped <- dropOld(xa, keepFrom).exit
      ahead <- ZIO.done(created)
      count <- ZIO.done(dropped)
    yield PartitionReport(
      movedFromDefault = ahead.map(_.moved).sum,
      daysLeftInDefault = ahead.collect { case Attach(day, _, false) => day },
      created = ahead.count(_.attached),
      dropped = count,
    )

  def ownership(xa: TransactorZIO): Task[TableOwnership] =
    xa.connectMeasured(s"check-$name-table-owner"):
      sql"""
        SELECT pg_get_userbyid(c.relowner)::text, current_user::text, pg_has_role(current_user, c.relowner, 'USAGE')
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = $table AND n.nspname = current_schema()
      """.query[(String, String, Boolean)].run().headOption
    .someOrFail(IllegalStateException(s"the table $table does not exist: has the migration run?"))
      .map((owner, role, mayAlter) => TableOwnership(owner = owner, role = role, mayAlter = mayAlter))

  /** How many days after `today` (UTC) have a partition: what the writer has left before its rows go to the default. */
  def partitionDaysAhead(xa: TransactorZIO, today: LocalDate): Task[Int] =
    xa.transactMeasured(s"count-$name-partitions-ahead"):
      timed()
      partitionDays.count(_.isAfter(today))

  /** Deletes the rows older than `before` from the default partition, in batches, each its own transaction under its own
    * timeout, and a run stops after `MaxBatches` (the next one goes on); how many it deleted. The default holds the rows of days
    * that had no partition when they were written, of any age, and is never dropped, so its rows are this method's alone. */
  def pruneDefault(xa: TransactorZIO, before: Instant): Task[Long] =
    val limit = before.atOffset(ZoneOffset.UTC)
    val step =
      xa.transactMeasured(s"prune-$name-default-events"):
        timed()
        guarded()
        sql"""
          DELETE FROM $defaultPartition
          WHERE id IN (SELECT id FROM $defaultPartition WHERE at < $limit LIMIT $batch)
        """.update.run()
    def loop(remaining: Int, total: Long): Task[Long] =
      step.flatMap(deleted => if deleted >= batch && remaining > 1 then loop(remaining - 1, total + deleted) else ZIO.succeed(total + deleted))
    loop(MaxBatches, 0L)

  /** The result of making one day: what was moved into it, and whether it is a partition now. */
  private case class Attach(day: LocalDate, moved: Long, attached: Boolean)

  /** A day's table is made on its own, attached, and only then is it a partition. Attaching takes SHARE UPDATE EXCLUSIVE on the
    * table (PostgreSQL 12 and later; creating the table `PARTITION OF` or `DETACH`ing takes ACCESS EXCLUSIVE), which conflicts
    * with neither the reads nor the writer's inserts, so the writer is never held up by it. What it does take is ACCESS EXCLUSIVE
    * on the default partition (to check that no row there belongs to the new day), so it waits behind a read of the table (the
    * default is part of every one) up to `lock_timeout`, and the reads that start meanwhile wait behind it; the writer does not,
    * unless it is writing to the default. A day's rows that the default holds are moved into its table first, since `ATTACH`
    * fails when there are some; a table whose rows are never moved leaves such a day unattached and reports it. */
  private def createAhead(xa: TransactorZIO, from: LocalDate, days: Int): Task[List[Attach]] =
    for
      present <- xa.connectMeasured(s"list-$name-partitions")(partitionDays.toSet)
      made <- ZIO.foreach((0 until days).map(from.plusDays(_)).filterNot(present).toList)(attachDay(xa, _))
    yield made

  private def attachDay(xa: TransactorZIO, day: LocalDate): Task[Attach] =
    for
      _ <- ZIO.when(defaultRows == DefaultRows.MoveOnAttach)(moveBulk(xa, day))
      // Read without a lock first: a day whose rows stay in the default is not worth holding the default up for.
      held <- ZIO.when(defaultRows == DefaultRows.LeaveAndReport)(countHeld(xa, day)).map(_.getOrElse(false))
      result <-
        if held then ZIO.succeed(Attach(day, 0L, attached = false))
        else
          xa.transactMeasured(s"attach-$name-partition"):
            lockedForMaintenance(lockTimeout)
            if partitionDays.contains(day) then Attach(day, 0L, attached = false) // another instance was first, and reports what it moved
            else
              createStandalone(day)
              val target = SqlLiteral(partitionName(day))
              // Counted before the lock, so that the scan does not hold it: what a bulk move left there, and what an earlier run
              // that moved the rows and then could not attach did.
              val earlier = sql"SELECT COUNT(*) FROM $target".query[Long].run().head
              // Taken now, not left to ATTACH, so that no write to the default can land between the move (or the check) and the
              // attach and make the attach fail on the row (the writes that wait for it are the events of the day that has no
              // partition yet).
              sql"LOCK TABLE $defaultPartition IN ACCESS EXCLUSIVE MODE".update.run()
              defaultRows match
                case DefaultRows.LeaveAndReport if defaultHoldsDay(day) => Attach(day, 0L, attached = false)
                case _ =>
                  val residual = if defaultRows == DefaultRows.MoveOnAttach then moveFromDefault(day, limit = None) else 0L
                  val start = SqlLiteral(s"'${day} 00:00:00+00'")
                  val end = SqlLiteral(s"'${day.plusDays(1)} 00:00:00+00'")
                  sql"ALTER TABLE $relation ATTACH PARTITION $target FOR VALUES FROM ($start) TO ($end)".update.run()
                  // It did its work: the attach found the bound implied and did not scan the table. A partition does not need it
                  // (and a table that was there before, made by hand or by an older run, may not have it).
                  sql"ALTER TABLE $target DROP CONSTRAINT IF EXISTS ${SqlLiteral(boundsName(day))}".update.run()
                  Attach(day, earlier + residual, attached = true)
      _ <- ZIO.when(result.moved > 0)(
        ZIO.logWarning(
          s"${result.moved} $name events of $day were written to ${table}_default, since the day had no partition, and are moved into its partition now",
        ),
      )
    yield result

  private def countHeld(xa: TransactorZIO, day: LocalDate): Task[Boolean] =
    xa.transactMeasured(s"count-$name-default-events"):
      timed()
      defaultHoldsDay(day)

  private def defaultHoldsDay(day: LocalDate)(using DbCon): Boolean =
    val start = day.atStartOfDay().atOffset(ZoneOffset.UTC)
    val end = day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
    sql"SELECT EXISTS (SELECT 1 FROM $defaultPartition WHERE at >= $start AND at < $end)".query[Boolean].run().head

  /** The table of a day, not yet a partition. The bounds are a CHECK constraint, so that `ATTACH PARTITION` finds them implied
    * and does not scan the table. `LIKE ... INCLUDING ALL` gives it the parent's indexes, which attach as they are. */
  private def createStandalone(day: LocalDate)(using DbCon): Unit =
    val target = SqlLiteral(partitionName(day))
    val start = SqlLiteral(s"'${day} 00:00:00+00'")
    val end = SqlLiteral(s"'${day.plusDays(1)} 00:00:00+00'")
    val bounds = SqlLiteral(boundsName(day))
    sql"CREATE TABLE IF NOT EXISTS $target (LIKE $relation INCLUDING ALL, CONSTRAINT $bounds CHECK (at >= $start AND at < $end))".update.run()
    ()

  /** Moves a day's rows out of the default partition into the day's table, at most `limit` of them; returns how many. */
  private def moveFromDefault(day: LocalDate, limit: Option[Int])(using DbCon): Long =
    val target = SqlLiteral(partitionName(day))
    val start = day.atStartOfDay().atOffset(ZoneOffset.UTC)
    val end = day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
    val capped = limit match
      case Some(n) => sql"AND id IN (SELECT id FROM $defaultPartition WHERE at >= $start AND at < $end LIMIT $n)"
      case None => sql""
    guarded()
    sql"""
      WITH moved AS (
        DELETE FROM $defaultPartition WHERE at >= $start AND at < $end $capped RETURNING *
      )
      INSERT INTO $target SELECT * FROM moved
    """.update.run().toLong

  /** After an outage of days the default may hold a day's rows by the million. Moving them in the attach's transaction would hold
    * the default's lock for as long, and the writes to it (the events of the day that has no partition) would wait that long, past
    * their statement timeout. So when there are more than a batch, the most of them go in batches of their own, each under its
    * own timeout, and the attach moves what is left. */
  private def moveBulk(xa: TransactorZIO, day: LocalDate): Task[Unit] =
    val start = day.atStartOfDay().atOffset(ZoneOffset.UTC)
    val end = day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
    val many = xa.transactMeasured(s"count-$name-default-events"):
      timed()
      sql"SELECT COUNT(*) FROM (SELECT 1 FROM $defaultPartition WHERE at >= $start AND at < $end LIMIT $batch) few"
        .query[Long].run().head >= batch
    val step = xa.transactMeasured(s"move-$name-default-events"):
      lockedForMaintenance(lockTimeout)
      timed()
      createStandalone(day)
      moveFromDefault(day, Some(batch))
    def loop(remaining: Int): Task[Unit] =
      step.flatMap(moved => if moved >= batch && remaining > 1 then loop(remaining - 1) else ZIO.unit)
    many.flatMap(ZIO.when(_)(loop(MaxBatches))).unit

  /** An old day goes in two steps, each its own transaction, so that neither waits for a lock while holding the other's.
    * `DETACH PARTITION` takes ACCESS EXCLUSIVE on the table (and on the default partition), instantly if nothing reads, so it is
    * tried under the short `detachLockTimeout`: the writer's inserts queue behind it for at most that. It is not `CONCURRENTLY`
    * (SHARE UPDATE EXCLUSIVE only, PostgreSQL 14 and later): that is refused while the table has a default partition. Dropping the
    * detached table then takes ACCESS EXCLUSIVE on that table alone, so a long read of that old day holds up its drop and nothing
    * else, under `lockTimeout`. A day whose drop failed, or whose run was cut off between the two, is a table that is no
    * partition: the next run drops it. A day that fails does not stop the others; the first failure is the one reported. The
    * result is how many were dropped. */
  private def dropOld(xa: TransactorZIO, keepFrom: LocalDate): Task[Int] =
    for
      (attached, loose) <- xa.connectMeasured(s"list-old-$name-partitions"):
        (partitionDays.filter(_.isBefore(keepFrom)).sorted, looseTableDays.filter(_.isBefore(keepFrom)).sorted)
      exits <- ZIO.foreach(attached.map(day => detach(xa, day) *> dropTable(xa, day)) ++ loose.map(dropTable(xa, _)))(_.exit)
      _ <- exits.collectFirst { case failure @ Exit.Failure(_) => failure }.fold(ZIO.unit)(ZIO.done(_))
    yield exits.count(_.isSuccess)

  private def detach(xa: TransactorZIO, day: LocalDate): Task[Unit] =
    xa.transactMeasured(s"detach-$name-partition"):
      lockedForMaintenance(lockTimeout)
      // Only now the lock that stalls the writer is asked for, and not for long.
      sql"SET LOCAL lock_timeout = ${SqlLiteral(s"'${detachLockTimeout.toMillis}ms'")}".update.run()
      // Another instance may have done it.
      if partitionDays.contains(day) then
        sql"ALTER TABLE $relation DETACH PARTITION ${SqlLiteral(partitionName(day))}".update.run()
      ()

  private def dropTable(xa: TransactorZIO, day: LocalDate): Task[Unit] =
    xa.transactMeasured(s"drop-$name-partition"):
      lockedForMaintenance(lockTimeout)
      sql"DROP TABLE IF EXISTS ${SqlLiteral(partitionName(day))}".update.run()
      ()

  /** Several instances run this at start-up; two of them making the same table at once can fail in the catalog, so they take
    * turns. Whatever lock the statements after it wait for, they wait at most `timeout` (`lock_timeout`, which also bounds the
    * wait for this lock): a maintenance that cannot get one gives up and the next run tries again soon. */
  private def lockedForMaintenance(timeout: Duration)(using DbCon): Unit =
    sql"SET LOCAL lock_timeout = ${SqlLiteral(s"'${timeout.toMillis}ms'")}".update.run()
    sql"SELECT 1 FROM (SELECT pg_advisory_xact_lock($AdvisoryLock)) locked".query[Int].run()
    ()

  /** The days that have a partition; the default partition is not a day. */
  def partitionDays(using DbCon): List[LocalDate] =
    sql"""
      SELECT c.relname
      FROM pg_inherits i
      JOIN pg_class c ON c.oid = i.inhrelid
      JOIN pg_class p ON p.oid = i.inhparent
      JOIN pg_namespace n ON n.oid = p.relnamespace
      WHERE p.relname = $table AND n.nspname = current_schema()
    """.query[String].run().toList.flatMap(partitionDay)

  /** Days of the tables named like a partition that are not one: what a run cut off between detaching and dropping leaves. */
  private def looseTableDays(using DbCon): List[LocalDate] =
    sql"""
      SELECT c.relname
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = current_schema() AND c.relkind = 'r' AND NOT c.relispartition AND c.relname ~ $loosePattern
    """.query[String].run().toList.flatMap(partitionDay)

object DailyPartitions:
  /** What a day's rows in the default partition mean when the day's partition is made. */
  enum DefaultRows:
    /** The rows are moved into the day's partition as it is attached (the antifraud log): nothing reads the table from outside. */
    case MoveOnAttach

    /** The rows are never moved (the audit log): an insert-only logical replication of the table would see the move as a second
      * insert of the same key. A day whose rows sit in the default is not attached, and is reported so that the service alerts
      * (`audit_events_in_default`); its rows go when they are past the retention. */
    case LeaveAndReport

  type Report = PartitionReport
  val Report: PartitionReport.type = PartitionReport

  /** Held for the length of a maintenance transaction; any constant would do. Shared by every table a service maintains in one
    * database, which only makes their maintenance take turns. */
  private val AdvisoryLock = 7430001L

  /** Batches of one prune run, or of moving a day's rows out of the default partition: with a pace of minutes that is far more
    * than the rows a day can hold, and it bounds the run. */
  private val MaxBatches = 200

  private val suffix = DateTimeFormatter.BASIC_ISO_DATE

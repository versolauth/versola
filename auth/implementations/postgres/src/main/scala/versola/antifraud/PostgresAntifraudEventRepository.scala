package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.util.postgres.BasicCodecs
import zio.{Duration, Exit, Task, ZIO, ZLayer, durationInt}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneOffset}

/** @param lockTimeout       how long partition maintenance waits for a lock that does not stall the writer: the other instances'
  *                          maintenance, autovacuum and other DDL, and (to attach a day) the default partition, which a read of
  *                          `af_event` holds
  * @param detachLockTimeout how long the detach of an old day waits for its locks. Detaching takes ACCESS EXCLUSIVE on `af_event`,
  *                          which the writer's inserts queue behind, so this is the longest the writer can be held up by
  *                          one attempt: short, and a failed attempt is retried by the next run
  * @param statementTimeout the longest one statement of the writer, the rollup or the prune may take: a statement
  *                         that hangs (a partition being locked, a slow disk) would otherwise hold the single writer
  *                         fiber while the queue fills and drops
  */
class PostgresAntifraudEventRepository(
    xa: TransactorZIO,
    lockTimeout: Duration = 5.seconds,
    detachLockTimeout: Duration = 500.millis,
    statementTimeout: Duration = 30.seconds,
    pruneBatch: Int = 5000,
) extends AntifraudEventRepository,
    BasicCodecs:
  import PgCodec.SeqCodec
  import PostgresAntifraudEventRepository.*
  import SqlArrayCodec.StringSqlArrayCodec

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private def timed()(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()

  override def insertBatch(events: List[AntifraudEvent]): Task[Unit] =
    if events.isEmpty then ZIO.unit
    else
      xa.transactMeasured("insert-antifraud-events"):
        timed()
        batchUpdate(events): event =>
          sql"""
            INSERT INTO af_event (id, at, tenant_id, client_id, conversation_id, action, outcome, channel,
                                  subject_hash, dest_prefix, user_agent, decision, score, rules, would_action, asn, asn_class, country)
            VALUES (
              ${event.id}, ${event.at}, ${event.tenantId.map(identity[String])}, ${event.clientId: String},
              ${event.conversationId}, ${event.action.toString}, ${event.outcome.toString},
              ${event.channel.toString}, ${event.subjectHash}, ${event.destPrefix}, ${event.userAgent},
              ${event.decision}, ${event.score}, ${event.rules}, ${event.wouldAction}, ${event.asn}, ${event.asnClass}, ${event.country}
            )
          """.update
      .unit

  override def pruneEvents(before: Instant): Task[Unit] =
    // The partition the limit falls in is the only one that can hold rows older than it (the ones before are dropped); the
    // lower bound lets the planner leave every other partition alone. A day's rows are many on a busy deployment, and the first run
    // after an upgrade would delete all of them in one statement and time out without progress, every time: so it goes in batches,
    // each its own transaction under its own timeout, and a run stops after `MaxPruneBatches` (the next one goes on).
    val dayStart = before.atOffset(ZoneOffset.UTC).toLocalDate.atStartOfDay().atOffset(ZoneOffset.UTC)
    val limit = before.atOffset(ZoneOffset.UTC)
    val batch =
      xa.transactMeasured("prune-antifraud-events"):
        timed()
        sql"""
          DELETE FROM af_event
          WHERE at >= $dayStart AND at < $limit
            AND id IN (SELECT id FROM af_event WHERE at >= $dayStart AND at < $limit LIMIT $pruneBatch)
        """.update.run()
    // The default partition holds the rows of days that had no partition when they were written, of any age: the day the limit
    // falls in is not the only one that can have rows past it, and the partition is never dropped.
    val defaultBatch =
      xa.transactMeasured("prune-antifraud-default-events"):
        timed()
        sql"""
          DELETE FROM $DefaultPartition
          WHERE id IN (SELECT id FROM $DefaultPartition WHERE at < $limit LIMIT $pruneBatch)
        """.update.run()
    def loop(step: Task[Int], remaining: Int): Task[Unit] =
      step.flatMap(deleted => if deleted >= pruneBatch && remaining > 1 then loop(step, remaining - 1) else ZIO.unit)
    loop(batch, MaxPruneBatches) *> loop(defaultBatch, MaxPruneBatches)

  override def rollupStats(from: Instant, to: Instant, prefixLength: Int, interval: Duration): Task[Boolean] =
    xa.transactMeasured("rollup-antifraud-stats"):
      timed()
      val locked = sql"SELECT pg_try_advisory_xact_lock($RollupLock)".query[Boolean].run().head
      // Under the lock, so that two instances cannot both find the marker old. Half an interval, not a whole one: the
      // instances sleep one interval each from their own start, so the next run of the one that ran last is a little over
      // an interval after its marker, and a whole interval would skip it half the time. The database's clock is the one
      // that is compared, so the instances' clocks need not agree.
      val halfInterval = SqlLiteral(s"${interval.toMillis / 2} * interval '1 millisecond'")
      val fresh = locked && sql"SELECT EXISTS (SELECT 1 FROM af_rollup_state WHERE id = 1 AND last_run > clock_timestamp() - $halfInterval)"
        .query[Boolean].run().head
      if !locked || fresh then false
      else
        val fromAt = from.atOffset(ZoneOffset.UTC)
        val toAt = to.atOffset(ZoneOffset.UTC)
        sql"""
          INSERT INTO af_dest_stats (tenant_id, dest_prefix, minute, requests, sent, suppressed, throttled,
                                     provider_errors, verified, wrong_codes, fake_verifies)
          SELECT COALESCE(tenant_id, ''), left(dest_prefix, $prefixLength), date_trunc('minute', at),
                 -- A challenge is a step of a request, not a request: the code that follows it is the one counted.
                 COUNT(*) FILTER (WHERE action = 'otp_request' AND outcome NOT IN ('challenged', 'challenge_passed', 'challenge_failed')),
                 COUNT(*) FILTER (WHERE outcome = 'sent'),
                 COUNT(*) FILTER (WHERE outcome = 'suppressed'),
                 COUNT(*) FILTER (WHERE outcome IN ('throttled', 'locked')),
                 COUNT(*) FILTER (WHERE outcome = 'provider_error'),
                 COUNT(*) FILTER (WHERE outcome = 'verified'),
                 COUNT(*) FILTER (WHERE outcome = 'wrong_code'),
                 COUNT(*) FILTER (WHERE outcome = 'fake_verify')
          FROM af_event
          WHERE channel = 'sms' AND dest_prefix IS NOT NULL AND at >= $fromAt AND at < $toAt
          GROUP BY 1, 2, 3
          ON CONFLICT (tenant_id, dest_prefix, minute) DO UPDATE SET
            requests = EXCLUDED.requests, sent = EXCLUDED.sent, suppressed = EXCLUDED.suppressed,
            throttled = EXCLUDED.throttled, provider_errors = EXCLUDED.provider_errors,
            verified = EXCLUDED.verified, wrong_codes = EXCLUDED.wrong_codes, fake_verifies = EXCLUDED.fake_verifies
          -- A minute that did not change is not rewritten: every run recomputes the lookback, and most of its groups are
          -- what the last run left, which would otherwise be a dead tuple each.
          WHERE (af_dest_stats.requests, af_dest_stats.sent, af_dest_stats.suppressed, af_dest_stats.throttled,
                 af_dest_stats.provider_errors, af_dest_stats.verified, af_dest_stats.wrong_codes, af_dest_stats.fake_verifies)
            IS DISTINCT FROM
                (EXCLUDED.requests, EXCLUDED.sent, EXCLUDED.suppressed, EXCLUDED.throttled,
                 EXCLUDED.provider_errors, EXCLUDED.verified, EXCLUDED.wrong_codes, EXCLUDED.fake_verifies)
        """.update.run()
        sql"""
          INSERT INTO af_rollup_state (id, last_run) VALUES (1, clock_timestamp())
          ON CONFLICT (id) DO UPDATE SET last_run = EXCLUDED.last_run
        """.update.run()
        true

  override def pruneStats(before: Instant): Task[Unit] =
    xa.transactMeasured("prune-antifraud-stats"):
      timed()
      sql"DELETE FROM af_dest_stats WHERE minute < ${before.atOffset(ZoneOffset.UTC)}".update.run()
    .unit

  /** The creates are one phase and the drops another: a drop that cannot take its lock (a long read on the partition
    * of an old day, a dependent object) must not stop the partitions of the days ahead, or the writer would run out
    * of them after the few days it is ahead. The drops are tried even when the creates failed; the first failure is the
    * one reported. The result is the number of rows moved out of the default partition. */
  override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Long] =
    for
      created <- createAhead(from, days).exit
      dropped <- dropOld(keepFrom).exit
      moved <- ZIO.done(created) <* ZIO.done(dropped)
    yield moved

  override def ownership: Task[TableOwnership] =
    xa.connectMeasured("check-antifraud-table-owner"):
      sql"""
        SELECT pg_get_userbyid(c.relowner)::text, current_user::text, pg_has_role(current_user, c.relowner, 'USAGE')
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = 'af_event' AND n.nspname = current_schema()
      """.query[(String, String, Boolean)].run().headOption
    .someOrFail(IllegalStateException("the table af_event does not exist: has the migration run?"))
      .map((owner, role, mayAlter) => TableOwnership(owner = owner, role = role, mayAlter = mayAlter))

  /** A day's table is made on its own, attached, and only then is it a partition. Attaching takes SHARE UPDATE EXCLUSIVE on
    * `af_event` (PostgreSQL 12 and later; creating the table `PARTITION OF` or `DETACH`ing takes ACCESS EXCLUSIVE), which conflicts
    * with neither the reads nor the writer's inserts, so the writer is never held up by it. What it does take is ACCESS EXCLUSIVE on
    * the default partition (to check that no row there belongs to the new day), so it waits behind a read of `af_event` (the default
    * is part of every one) up to `lock_timeout`, and the reads that start meanwhile wait behind it; the writer does not, unless it is
    * writing to the default. A day's rows that the default holds are moved into its table first, since `ATTACH` fails when there are some. */
  private def createAhead(from: LocalDate, days: Int): Task[Long] =
    for
      present <- xa.connectMeasured("list-antifraud-partitions")(partitionDays().toSet)
      moved <- ZIO.foreach((0 until days).map(from.plusDays(_)).filterNot(present).toList)(attachDay)
    yield moved.sum

  private def attachDay(day: LocalDate): Task[Long] =
    for
      _ <- moveBulk(day)
      moved <- xa.transactMeasured("attach-antifraud-partition"):
        lockedForMaintenance(lockTimeout)
        if partitionDays().contains(day) then 0L // another instance was first, and reports what it moved
        else
          createStandalone(day)
          val table = SqlLiteral(partitionName(day))
          // Counted before the lock, so that the scan does not hold it: what a bulk move left there, and what an earlier run that
          // moved the rows and then could not attach did.
          val earlier = sql"SELECT COUNT(*) FROM $table".query[Long].run().head
          // Taken now, not left to ATTACH, so that no write to the default can land between the move and the attach and make the
          // attach fail on the row (the writes that wait for it are the events of the day that has no partition yet).
          sql"LOCK TABLE $DefaultPartition IN ACCESS EXCLUSIVE MODE".update.run()
          val residual = moveFromDefault(day, limit = None)
          val start = SqlLiteral(s"'${day} 00:00:00+00'")
          val end = SqlLiteral(s"'${day.plusDays(1)} 00:00:00+00'")
          sql"ALTER TABLE af_event ATTACH PARTITION $table FOR VALUES FROM ($start) TO ($end)".update.run()
          // It did its work: the attach found the bound implied and did not scan the table. A partition does not need it (and a table that was
          // there before, made by hand or by an older run, may not have it).
          sql"ALTER TABLE $table DROP CONSTRAINT IF EXISTS ${SqlLiteral(boundsName(day))}".update.run()
          earlier + residual
      _ <- ZIO.when(moved > 0)(
        ZIO.logWarning(
          s"$moved antifraud events of $day were written to af_event_default, since the day had no partition, and are moved into its partition now",
        ),
      )
    yield moved

  /** The table of a day, not yet a partition. The bounds are a CHECK constraint, so that `ATTACH PARTITION` finds them implied
    * and does not scan the table. `LIKE ... INCLUDING ALL` gives it the parent's indexes, which attach as they are. */
  private def createStandalone(day: LocalDate)(using DbCon): Unit =
    val table = SqlLiteral(partitionName(day))
    val start = SqlLiteral(s"'${day} 00:00:00+00'")
    val end = SqlLiteral(s"'${day.plusDays(1)} 00:00:00+00'")
    val bounds = SqlLiteral(boundsName(day))
    sql"CREATE TABLE IF NOT EXISTS $table (LIKE af_event INCLUDING ALL, CONSTRAINT $bounds CHECK (at >= $start AND at < $end))".update.run()
    ()

  /** Moves a day's rows out of the default partition into the day's table, at most `limit` of them; returns how many. */
  private def moveFromDefault(day: LocalDate, limit: Option[Int])(using DbCon): Long =
    val table = SqlLiteral(partitionName(day))
    val start = day.atStartOfDay().atOffset(ZoneOffset.UTC)
    val end = day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
    val capped = limit match
      case Some(n) => sql"AND id IN (SELECT id FROM $DefaultPartition WHERE at >= $start AND at < $end LIMIT $n)"
      case None => sql""
    sql"""
      WITH moved AS (
        DELETE FROM $DefaultPartition WHERE at >= $start AND at < $end $capped RETURNING *
      )
      INSERT INTO $table SELECT * FROM moved
    """.update.run().toLong

  /** After an outage of days the default may hold a day's rows by the million. Moving them in the attach's transaction would hold
    * the default's lock for as long, and the writes to it (the events of the day that has no partition) would wait that long, past
    * their statement timeout. So when there are more than a batch, the most of them go in batches of their own, each under its
    * own timeout, and the attach moves what is left. */
  private def moveBulk(day: LocalDate): Task[Unit] =
    val start = day.atStartOfDay().atOffset(ZoneOffset.UTC)
    val end = day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
    val many = xa.transactMeasured("count-antifraud-default-events"):
      timed()
      sql"SELECT COUNT(*) FROM (SELECT 1 FROM $DefaultPartition WHERE at >= $start AND at < $end LIMIT $pruneBatch) few"
        .query[Long].run().head >= pruneBatch
    val batch = xa.transactMeasured("move-antifraud-default-events"):
      lockedForMaintenance(lockTimeout)
      timed()
      createStandalone(day)
      moveFromDefault(day, Some(pruneBatch))
    def loop(remaining: Int): Task[Unit] =
      batch.flatMap(moved => if moved >= pruneBatch && remaining > 1 then loop(remaining - 1) else ZIO.unit)
    many.flatMap(ZIO.when(_)(loop(MaxPruneBatches))).unit

  /** An old day goes in two steps, each its own transaction, so that neither waits for a lock while holding the other's.
    * `DETACH PARTITION` takes ACCESS EXCLUSIVE on `af_event` (and on the default partition), instantly if nothing reads, so it is
    * tried under the short `detachLockTimeout`: the writer's inserts queue behind it for at most that. It is not `CONCURRENTLY`
    * (SHARE UPDATE EXCLUSIVE only, PostgreSQL 14 and later): that is refused while the table has a default partition. Dropping the
    * detached table then takes ACCESS EXCLUSIVE on that table alone, so a long read of that old day holds up its drop and nothing
    * else, under `lockTimeout`. A day whose drop failed, or whose run was cut off between the two, is a table that is no
    * partition: the next run drops it. A day that fails does not stop the others; the first failure is the one reported. */
  private def dropOld(keepFrom: LocalDate): Task[Unit] =
    for
      (attached, loose) <- xa.connectMeasured("list-old-antifraud-partitions"):
        (partitionDays().filter(_.isBefore(keepFrom)).sorted, looseTableDays().filter(_.isBefore(keepFrom)).sorted)
      exits <- ZIO.foreach(attached.map(day => detach(day) *> dropTable(day)) ++ loose.map(dropTable))(_.exit)
      _ <- exits.collectFirst { case failure @ Exit.Failure(_) => failure }.fold(ZIO.unit)(ZIO.done(_))
    yield ()

  private def detach(day: LocalDate): Task[Unit] =
    xa.transactMeasured("detach-antifraud-partition"):
      lockedForMaintenance(lockTimeout)
      // Only now the lock that stalls the writer is asked for, and not for long.
      sql"SET LOCAL lock_timeout = ${SqlLiteral(s"'${detachLockTimeout.toMillis}ms'")}".update.run()
      // Another instance may have done it.
      if partitionDays().contains(day) then
        sql"ALTER TABLE af_event DETACH PARTITION ${SqlLiteral(partitionName(day))}".update.run()
      ()

  private def dropTable(day: LocalDate): Task[Unit] =
    xa.transactMeasured("drop-antifraud-partition"):
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

  private def partitionDays()(using DbCon): List[LocalDate] =
    sql"""
      SELECT c.relname
      FROM pg_inherits i
      JOIN pg_class c ON c.oid = i.inhrelid
      JOIN pg_class p ON p.oid = i.inhparent
      JOIN pg_namespace n ON n.oid = p.relnamespace
      WHERE p.relname = 'af_event' AND n.nspname = current_schema()
    """.query[String].run().toList.flatMap(partitionDay)

  /** Days of the tables named like a partition that are not one: what a run cut off between detaching and dropping leaves. */
  private def looseTableDays()(using DbCon): List[LocalDate] =
    sql"""
      SELECT c.relname
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = current_schema() AND c.relkind = 'r' AND NOT c.relispartition AND c.relname ~ '^af_event_[0-9]{8}'
    """.query[String].run().toList.flatMap(partitionDay)

  override def partitionDaysAhead(today: LocalDate): Task[Int] =
    xa.transactMeasured("count-antifraud-partitions-ahead"):
      timed()
      partitionDays().count(_.isAfter(today))

object PostgresAntifraudEventRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudEventRepository] =
    ZLayer.fromFunction(PostgresAntifraudEventRepository(_))

  /** Held for the length of a maintenance transaction; any constant would do. */
  private val AdvisoryLock = 7430001L

  private val RollupLock = 7430002L

  /** Batches of one prune run, or of moving a day's rows out of the default partition: with the 5-minute pace that is far more than the rows a day can hold, and it bounds the run. */
  private val MaxPruneBatches = 200

  private val suffix = DateTimeFormatter.BASIC_ISO_DATE

  private[antifraud] def partitionName(day: LocalDate): String = s"af_event_${suffix.format(day)}"

  /** The CHECK that carries a day's bounds while its table is not yet a partition. */
  private def boundsName(day: LocalDate): String = s"${partitionName(day)}_day"

  /** Where the rows of a day that has no partition go (migration `V0030`). Never dropped; [[partitionDay]] does not read it as a day. */
  private val DefaultPartition = SqlLiteral("af_event_default")

  private val partitionPattern = raw"af_event_(\d{8})".r

  /** The day a partition holds, `None` for a table that is not one of this service's partitions. */
  private[antifraud] def partitionDay(name: String): Option[LocalDate] =
    name match
      case partitionPattern(digits) => scala.util.Try(LocalDate.parse(digits, suffix)).toOption
      case _ => None

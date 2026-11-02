package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZIO, ZLayer, durationInt}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneOffset}

/** @param lockTimeout      how long partition maintenance waits for the lock on `af_event`
  * @param statementTimeout the longest one statement of the writer, the rollup or the prune may take: a statement
  *                         that hangs (a partition being locked, a slow disk) would otherwise hold the single writer
  *                         fiber while the queue fills and drops
  */
class PostgresAntifraudEventRepository(xa: TransactorZIO, lockTimeout: Duration = 5.seconds, statementTimeout: Duration = 30.seconds)
  extends AntifraudEventRepository,
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
                                  subject_hash, dest_prefix, user_agent, decision, score, rules)
            VALUES (
              ${event.id}, ${event.at}, ${event.tenantId.map(identity[String])}, ${event.clientId: String},
              ${event.conversationId}, ${event.action.toString}, ${event.outcome.toString},
              ${event.channel.toString}, ${event.subjectHash}, ${event.destPrefix}, ${event.userAgent},
              ${event.decision}, ${event.score}, ${event.rules}
            )
          """.update
      .unit

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

  /** The creates are one transaction and the drops another: a drop that cannot take its lock (a long read on the partition
    * of an old day, a dependent object) must not roll back the partitions of the days ahead, or the writer would run out
    * of them after the few days it is ahead. The drops are tried even when the creates failed; the first failure is the
    * one reported. */
  override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Unit] =
    for
      created <- createAhead(from, days).exit
      dropped <- dropOld(keepFrom).exit
      _ <- ZIO.done(created) *> ZIO.done(dropped)
    yield ()

  private def createAhead(from: LocalDate, days: Int): Task[Unit] =
    xa.transactMeasured("create-antifraud-partitions"):
      lockedForMaintenance()
      (0 until days).map(from.plusDays(_)).foreach: day =>
        val partition = SqlLiteral(partitionName(day))
        val start = SqlLiteral(s"'${day} 00:00:00+00'")
        val end = SqlLiteral(s"'${day.plusDays(1)} 00:00:00+00'")
        sql"CREATE TABLE IF NOT EXISTS $partition PARTITION OF af_event FOR VALUES FROM ($start) TO ($end)".update.run()
    .unit

  private def dropOld(keepFrom: LocalDate): Task[Unit] =
    xa.transactMeasured("drop-antifraud-partitions"):
      lockedForMaintenance()
      partitionDays().filter(_.isBefore(keepFrom)).foreach: day =>
        val partition = SqlLiteral(partitionName(day))
        sql"DROP TABLE IF EXISTS $partition".update.run()
    .unit

  /** Several instances run this at start-up; creating the same partition twice at once can fail in the catalog, so they
    * take turns. Creating or dropping a partition takes an exclusive lock on `af_event`, which the writer's inserts queue
    * behind (they wait, up to `lock_timeout`, not past it): behind a long read it would hold up the writer, so after
    * `lock_timeout` the maintenance gives up and the next run tries again soon. */
  private def lockedForMaintenance()(using DbCon): Unit =
    val timeout = SqlLiteral(s"'${lockTimeout.toMillis}ms'")
    sql"SET LOCAL lock_timeout = $timeout".update.run()
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

  override def partitionDaysAhead(today: LocalDate): Task[Int] =
    xa.connectMeasured("count-antifraud-partitions-ahead"):
      partitionDays().count(_.isAfter(today))

object PostgresAntifraudEventRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudEventRepository] =
    ZLayer.fromFunction(PostgresAntifraudEventRepository(_))

  /** Held for the length of a maintenance transaction; any constant would do. */
  private val AdvisoryLock = 7430001L

  private val RollupLock = 7430002L

  private val suffix = DateTimeFormatter.BASIC_ISO_DATE

  private[antifraud] def partitionName(day: LocalDate): String = s"af_event_${suffix.format(day)}"

  private val partitionPattern = raw"af_event_(\d{8})".r

  /** The day a partition holds, `None` for a table that is not one of this service's partitions. */
  private[antifraud] def partitionDay(name: String): Option[LocalDate] =
    name match
      case partitionPattern(digits) => scala.util.Try(LocalDate.parse(digits, suffix)).toOption
      case _ => None

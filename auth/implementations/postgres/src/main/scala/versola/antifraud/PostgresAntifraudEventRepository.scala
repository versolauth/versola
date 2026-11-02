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
class PostgresAntifraudEventRepository(
    xa: TransactorZIO,
    lockTimeout: Duration = 5.seconds,
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
                                  subject_hash, dest_prefix, user_agent, decision, score, rules, would_action, asn, asn_class)
            VALUES (
              ${event.id}, ${event.at}, ${event.tenantId.map(identity[String])}, ${event.clientId: String},
              ${event.conversationId}, ${event.action.toString}, ${event.outcome.toString},
              ${event.channel.toString}, ${event.subjectHash}, ${event.destPrefix}, ${event.userAgent},
              ${event.decision}, ${event.score}, ${event.rules}, ${event.wouldAction}, ${event.asn}, ${event.asnClass}
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
    def loop(remaining: Int): Task[Unit] =
      batch.flatMap(deleted => if deleted >= pruneBatch && remaining > 1 then loop(remaining - 1) else ZIO.unit)
    loop(MaxPruneBatches)

  override def rollupStats(from: Instant, to: Instant, prefixLength: Int): Task[Boolean] =
    xa.transactMeasured("rollup-antifraud-stats"):
      timed()
      val locked = sql"SELECT pg_try_advisory_xact_lock($RollupLock)".query[Boolean].run().head
      if !locked then false
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
        """.update.run()
        true

  override def pruneStats(before: Instant): Task[Unit] =
    xa.transactMeasured("prune-antifraud-stats"):
      timed()
      sql"DELETE FROM af_dest_stats WHERE minute < ${before.atOffset(ZoneOffset.UTC)}".update.run()
    .unit

  override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Unit] =
    xa.transactMeasured("maintain-antifraud-partitions"):
      // Several instances run this at start-up; creating the same partition twice at once can fail in the
      // catalog, so they take turns.
      // Creating or dropping a partition takes an exclusive lock on `af_event`; behind a long read it would hold up
      // every insert of the writer. Better to give up (the next run tries again soon) than to queue them.
      val timeout = SqlLiteral(s"'${lockTimeout.toMillis}ms'")
      sql"SET LOCAL lock_timeout = $timeout".update.run()
      sql"SELECT 1 FROM (SELECT pg_advisory_xact_lock($AdvisoryLock)) locked".query[Int].run()
      (0 until days).map(from.plusDays(_)).foreach: day =>
        val partition = SqlLiteral(partitionName(day))
        val start = SqlLiteral(s"'${day} 00:00:00+00'")
        val end = SqlLiteral(s"'${day.plusDays(1)} 00:00:00+00'")
        sql"CREATE TABLE IF NOT EXISTS $partition PARTITION OF af_event FOR VALUES FROM ($start) TO ($end)".update.run()
      val existing = sql"""
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        JOIN pg_namespace n ON n.oid = p.relnamespace
        WHERE p.relname = 'af_event' AND n.nspname = current_schema()
      """.query[String].run()
      existing.flatMap(partitionDay).filter(_.isBefore(keepFrom)).foreach: day =>
        val partition = SqlLiteral(partitionName(day))
        sql"DROP TABLE IF EXISTS $partition".update.run()
    .unit

object PostgresAntifraudEventRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudEventRepository] =
    ZLayer.fromFunction(PostgresAntifraudEventRepository(_))

  /** Held for the length of a maintenance transaction; any constant would do. */
  private val AdvisoryLock = 7430001L

  private val RollupLock = 7430002L

  /** Batches of one prune run: with the 5-minute pace that is far more than the rows a day can hold, and it bounds the run. */
  private val MaxPruneBatches = 200

  private val suffix = DateTimeFormatter.BASIC_ISO_DATE

  private[antifraud] def partitionName(day: LocalDate): String = s"af_event_${suffix.format(day)}"

  private val partitionPattern = raw"af_event_(\d{8})".r

  /** The day a partition holds, `None` for a table that is not one of this service's partitions. */
  private[antifraud] def partitionDay(name: String): Option[LocalDate] =
    name match
      case partitionPattern(digits) => scala.util.Try(LocalDate.parse(digits, suffix)).toOption
      case _ => None

package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.util.partitions.TableOwnership
import versola.util.postgres.{BasicCodecs, DailyPartitions}
import zio.{Duration, Task, ZIO, ZLayer, durationInt}

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

  /** The partitions of `af_event`, with the day's rows the default partition holds moved into the day's partition as it is made. */
  private val partitions = DailyPartitions(
    table = "af_event",
    defaultRows = DailyPartitions.DefaultRows.MoveOnAttach,
    lockTimeout = lockTimeout,
    detachLockTimeout = detachLockTimeout,
    statementTimeout = statementTimeout,
    batch = pruneBatch,
    label = Some("antifraud"),
  )

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
    def loop(step: Task[Int], remaining: Int): Task[Unit] =
      step.flatMap(deleted => if deleted >= pruneBatch && remaining > 1 then loop(step, remaining - 1) else ZIO.unit)
    // The default partition holds the rows of days that had no partition when they were written, of any age: the day the limit
    // falls in is not the only one that can have rows past it, and the partition is never dropped.
    loop(batch, MaxPruneBatches) *> partitions.pruneDefault(xa, before).unit

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
                 -- A passkey passed in place of a sent code is that code's conversion.
                 COUNT(*) FILTER (WHERE outcome IN ('verified', 'passkey_alternative')),
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

  override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Long] =
    partitions.maintain(xa, from = from, days = days, keepFrom = keepFrom).map(_.movedFromDefault)

  override def ownership: Task[TableOwnership] = partitions.ownership(xa)

  override def partitionDaysAhead(today: LocalDate): Task[Int] = partitions.partitionDaysAhead(xa, today)

object PostgresAntifraudEventRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudEventRepository] =
    ZLayer.fromFunction(PostgresAntifraudEventRepository(_))

  private val RollupLock = 7430002L

  /** Batches of one prune run: with the 5-minute pace that is far more than the rows a day can hold, and it bounds the run. */
  private val MaxPruneBatches = 200

  /** The naming of the partitions, for what reads it without a repository. */
  private val naming = DailyPartitions(table = "af_event", defaultRows = DailyPartitions.DefaultRows.MoveOnAttach)

  private[antifraud] def partitionName(day: LocalDate): String = naming.partitionName(day)

  /** The day a partition holds, `None` for a table that is not one of this service's partitions (the default partition, `V0030`, included). */
  private[antifraud] def partitionDay(name: String): Option[LocalDate] = naming.partitionDay(name)

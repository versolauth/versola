package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZIO, ZLayer, durationInt}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneOffset}

class PostgresAntifraudEventRepository(xa: TransactorZIO, lockTimeout: Duration = 5.seconds) extends AntifraudEventRepository, BasicCodecs:
  import PgCodec.SeqCodec
  import PostgresAntifraudEventRepository.*
  import SqlArrayCodec.StringSqlArrayCodec

  private given DbCodec[Instant] = DbCodec.InstantCodec

  override def insertBatch(events: List[AntifraudEvent]): Task[Unit] =
    if events.isEmpty then ZIO.unit
    else
      xa.transactMeasured("insert-antifraud-events"):
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

  override def rollupStats(from: Instant, to: Instant, prefixLength: Int): Task[Boolean] =
    xa.transactMeasured("rollup-antifraud-stats"):
      val locked = sql"SELECT pg_try_advisory_xact_lock($RollupLock)".query[Boolean].run().head
      if !locked then false
      else
        val fromAt = from.atOffset(ZoneOffset.UTC)
        val toAt = to.atOffset(ZoneOffset.UTC)
        sql"""
          INSERT INTO af_dest_stats (tenant_id, dest_prefix, minute, requests, sent, suppressed, throttled,
                                     provider_errors, verified, wrong_codes)
          SELECT COALESCE(tenant_id, ''), left(dest_prefix, $prefixLength), date_trunc('minute', at),
                 COUNT(*) FILTER (WHERE action = 'otp_request'),
                 COUNT(*) FILTER (WHERE outcome = 'sent'),
                 COUNT(*) FILTER (WHERE outcome = 'suppressed'),
                 COUNT(*) FILTER (WHERE outcome IN ('throttled', 'locked')),
                 COUNT(*) FILTER (WHERE outcome = 'provider_error'),
                 COUNT(*) FILTER (WHERE outcome = 'verified'),
                 COUNT(*) FILTER (WHERE outcome = 'wrong_code')
          FROM af_event
          WHERE channel = 'sms' AND dest_prefix IS NOT NULL AND at >= $fromAt AND at < $toAt
          GROUP BY 1, 2, 3
          ON CONFLICT (tenant_id, dest_prefix, minute) DO UPDATE SET
            requests = EXCLUDED.requests, sent = EXCLUDED.sent, suppressed = EXCLUDED.suppressed,
            throttled = EXCLUDED.throttled, provider_errors = EXCLUDED.provider_errors,
            verified = EXCLUDED.verified, wrong_codes = EXCLUDED.wrong_codes
        """.update.run()
        true

  override def pruneStats(before: Instant): Task[Unit] =
    xa.connectMeasured("prune-antifraud-stats"):
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

  private val suffix = DateTimeFormatter.BASIC_ISO_DATE

  private[antifraud] def partitionName(day: LocalDate): String = s"af_event_${suffix.format(day)}"

  private val partitionPattern = raw"af_event_(\d{8})".r

  /** The day a partition holds, `None` for a table that is not one of this service's partitions. */
  private[antifraud] def partitionDay(name: String): Option[LocalDate] =
    name match
      case partitionPattern(digits) => scala.util.Try(LocalDate.parse(digits, suffix)).toOption
      case _ => None

package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.sql.Types
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, ZoneOffset}

class PostgresAntifraudEventRepository(xa: TransactorZIO) extends AntifraudEventRepository, BasicCodecs:
  import PostgresAntifraudEventRepository.*

  override def insertBatch(events: List[AntifraudEvent]): Task[Unit] =
    xa.connectMeasured("insert-antifraud-events"):
      val connection = summon[DbCon].connection
      val statement = connection.prepareStatement(
        """INSERT INTO af_event (id, at, tenant_id, client_id, conversation_id, action, outcome, channel,
          |                      subject_hash, dest_prefix, user_agent, decision, score, rules)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin,
      )
      try
        events.foreach: event =>
          statement.setObject(1, event.id)
          statement.setObject(2, event.at.atOffset(ZoneOffset.UTC))
          statement.setString(3, event.tenantId.map(identity[String]).orNull)
          statement.setString(4, event.clientId)
          statement.setObject(5, event.conversationId)
          statement.setString(6, event.action.toString)
          statement.setString(7, event.outcome.toString)
          statement.setString(8, event.channel.toString)
          statement.setString(9, event.subjectHash)
          statement.setString(10, event.destPrefix.orNull)
          statement.setString(11, event.userAgent.orNull)
          statement.setString(12, event.decision.orNull)
          event.score.fold(statement.setNull(13, Types.INTEGER))(score => statement.setInt(13, score))
          statement.setArray(14, connection.createArrayOf("text", event.rules.toArray[Object]))
          statement.addBatch()
        statement.executeBatch()
      finally statement.close()
    .unit

  override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[Unit] =
    xa.transactMeasured("maintain-antifraud-partitions"):
      // Several instances run this at start-up; creating the same partition twice at once can fail in the
      // catalog, so they take turns.
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

  private val suffix = DateTimeFormatter.BASIC_ISO_DATE

  private[antifraud] def partitionName(day: LocalDate): String = s"af_event_${suffix.format(day)}"

  private val partitionPattern = raw"af_event_(\d{8})".r

  /** The day a partition holds, `None` for a table that is not one of this service's partitions. */
  private[antifraud] def partitionDay(name: String): Option[LocalDate] =
    name match
      case partitionPattern(digits) => scala.util.Try(LocalDate.parse(digits, suffix)).toOption
      case _ => None

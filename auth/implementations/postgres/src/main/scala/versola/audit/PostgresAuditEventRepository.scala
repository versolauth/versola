package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.audit.AuditReadRepository.*
import versola.util.audit.*
import versola.util.partitions.{PartitionReport, TableOwnership}
import versola.util.postgres.{BasicCodecs, DailyPartitions}
import zio.json.*
import zio.json.ast.Json
import versola.util.http.IpAddress
import zio.metrics.Metric
import zio.{Duration, Task, ZIO, ZLayer, durationInt}

import java.time.{Instant, LocalDate}
import java.util.UUID

/** `audit_event` (migration `V0031`): written in batches by the audit writer, read by the per-user report, its partitions kept by
  * [[DailyPartitions]] with the default partition's rows left where they are (an insert-only replication would see a moved row
  * twice) and its deletes marked as the service's own maintenance, which the table's trigger otherwise refuses.
  *
  * @param statementTimeout the longest one statement may take: a statement that hangs (a partition being locked, a slow disk)
  *                         would otherwise hold the single writer while the queue fills and requests block
  */
class PostgresAuditEventRepository(
    xa: TransactorZIO,
    partitions: DailyPartitions,
    statementTimeout: Duration = 30.seconds,
) extends AuditEventRepository,
    AuditReadRepository,
    BasicCodecs:

  import PostgresAuditEventRepository.*

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private def timed()(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()

  /** The batch in one round trip. A batch the database refuses for what is in it (a data exception, SQLSTATE class 22) is written row by
    * row instead, and a row that is refused on its own is counted and logged without its content, never retried: the writer keeps what
    * it could not write and tries again, so a single row that can never be stored would otherwise stop the log for everyone, and what an
    * event carries is partly what a caller of the service sent. */
  override def insertBatch(events: List[AuditEvent]): Task[Unit] =
    if events.isEmpty then ZIO.unit
    else
      val clean = events.map(sanitized)
      insertTogether(clean).catchSome:
        case error if isDataException(error) => ZIO.foreachDiscard(clean)(insertAlone)

  private def insertTogether(events: List[AuditEvent]): Task[Unit] =
    xa.transactMeasured("insert-audit-events"):
      timed()
      batchUpdate(events)(insertStatement)
    .unit

  private def insertAlone(event: AuditEvent): Task[Unit] =
    xa.transactMeasured("insert-audit-event"):
      timed()
      insertStatement(event).run()
    .unit.catchSome:
      case error if isDataException(error) =>
        PostgresAuditEventRepository.rejected.increment *>
          ZIO.logError(
            s"an audit event of kind ${event.kind.value} (id ${event.id}) was refused by the database and is not stored: ${sqlState(error).getOrElse("data exception")}",
          )

  // `ip` and `details` are text bound to the column's type: INET and JSONB have no driver type of their own here.
  private def insertStatement(event: AuditEvent) =
    sql"""
      INSERT INTO audit_event (id, at, tenant_id, source, node_id, kind, outcome, reason, severity, actor_type, actor_id,
                               user_id, client_id, session_id, ip, user_agent, trace_id, target_type, target_id, details)
      VALUES (
        ${event.id}, ${event.at}, ${event.tenantId}, ${event.source.value}, ${event.nodeId}, ${event.kind.value},
        ${event.outcome.toString}, ${event.reason}, ${event.severity.toString}, ${event.actorType.value}, ${event.actorId},
        ${event.userId}, ${event.clientId}, ${event.sessionId}, CAST(${event.ip} AS inet), ${event.userAgent}, ${event.traceId},
        ${event.targetType}, ${event.targetId}, CAST(${event.details.toJson} AS jsonb)
      )
    """.update

  override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
    partitions.maintain(xa, from, days, keepFrom)

  override def partitionDaysAhead(today: LocalDate): Task[Int] =
    partitions.partitionDaysAhead(xa, today)

  override def pruneDefault(before: Instant): Task[Long] =
    partitions.pruneDefault(xa, before)

  override def ownership: Task[TableOwnership] =
    partitions.ownership(xa)

  // The columns of an event, in the order [[EventRow]] reads them; `ip` as text and `details` as JSON text.
  private val EventColumns = SqlLiteral(
    "id, at, tenant_id, source, node_id, kind, outcome, reason, severity, actor_type, actor_id, user_id, client_id, session_id, " +
      "host(ip), user_agent, trace_id, target_type, target_id, details::text",
  )

  private case class EventRow(
      id: UUID,
      at: Instant,
      tenantId: Option[String],
      source: String,
      nodeId: String,
      kind: String,
      outcome: String,
      reason: Option[String],
      severity: String,
      actorType: String,
      actorId: Option[String],
      userId: Option[UUID],
      clientId: Option[String],
      sessionId: Option[String],
      ip: Option[String],
      userAgent: Option[String],
      traceId: Option[String],
      targetType: Option[String],
      targetId: Option[String],
      details: String,
  ) derives DbCodec:
    def toEvent: AuditEvent =
      AuditEvent(
        id = id,
        at = at,
        tenantId = tenantId,
        source = AuditSource(source),
        nodeId = nodeId,
        kind = AuditKind(kind),
        outcome = AuditOutcome.valueOf(outcome),
        reason = reason,
        severity = AuditSeverity.valueOf(severity),
        actorType = ActorType(actorType),
        actorId = actorId,
        userId = userId,
        clientId = clientId,
        sessionId = sessionId,
        ip = ip,
        userAgent = userAgent,
        traceId = traceId,
        targetType = targetType,
        targetId = targetId,
        details = details.fromJson[Json.Obj].getOrElse(Json.Obj()),
      )

  override def userEvents(userId: UUID, from: Instant, to: Instant, after: Option[Position], limit: Int): Task[List[AuditEvent]] =
    xa.transactMeasured("find-audit-user-events"):
      timed()
      // The page boundary is a row comparison on the order the page is read in, so the next page starts exactly where this one
      // stopped even when several events share an instant.
      after match
        case None =>
          sql"""
            SELECT $EventColumns FROM audit_event
            WHERE user_id = $userId AND at >= $from AND at <= $to
            ORDER BY at DESC, id DESC LIMIT $limit
          """.query[EventRow].run().toList
        case Some(position) =>
          sql"""
            SELECT $EventColumns FROM audit_event
            WHERE user_id = $userId AND at >= $from AND at <= $to AND (at, id) < (${position.at}, ${position.id})
            ORDER BY at DESC, id DESC LIMIT $limit
          """.query[EventRow].run().toList
    .map(_.map(_.toEvent))

  override def userSummary(userId: UUID, from: Instant, to: Instant): Task[Summary] =
    xa.transactMeasured("summarize-audit-user-events"):
      timed()
      val byKind = sql"""
        SELECT kind, outcome, count(*) FROM audit_event
        WHERE user_id = $userId AND at >= $from AND at <= $to
        GROUP BY kind, outcome ORDER BY kind, outcome
      """.query[(String, String, Long)].run().toList.map(KindCount.apply.tupled)
      val byClient = sql"""
        SELECT client_id,
               count(*) FILTER (WHERE kind = 'login.completed' AND outcome = 'success'),
               count(*) FILTER (WHERE kind = 'token.issued' AND outcome = 'success')
        FROM audit_event
        WHERE user_id = $userId AND at >= $from AND at <= $to AND client_id IS NOT NULL
        GROUP BY client_id ORDER BY client_id
      """.query[(String, Long, Long)].run().toList.map(ClientCount.apply.tupled)
      val ips = sql"""
        SELECT host(ip), min(at), max(at), count(*) FROM audit_event
        WHERE user_id = $userId AND at >= $from AND at <= $to AND ip IS NOT NULL
        GROUP BY ip ORDER BY count(*) DESC, host(ip) LIMIT $TopLimit
      """.query[(String, Instant, Instant, Long)].run().toList.map(IpCount.apply.tupled)
      val userAgents = sql"""
        SELECT user_agent, min(at), max(at), count(*) FROM audit_event
        WHERE user_id = $userId AND at >= $from AND at <= $to AND user_agent IS NOT NULL
        GROUP BY user_agent ORDER BY count(*) DESC, user_agent LIMIT $TopLimit
      """.query[(String, Instant, Instant, Long)].run().toList.map(UserAgentCount.apply.tupled)
      val sessions = sql"""
        SELECT count(*) FILTER (WHERE kind = 'login.completed' AND outcome = 'success'),
               count(*) FILTER (WHERE kind = 'session.ended')
        FROM audit_event
        WHERE user_id = $userId AND at >= $from AND at <= $to
      """.query[(Long, Long)].run().headOption.fold(SessionCount(0, 0))(SessionCount.apply.tupled)
      val factors = sql"""
        SELECT kind, at FROM audit_event
        WHERE user_id = $userId AND at >= $from AND at <= $to AND (starts_with(kind, 'account.password_') OR starts_with(kind, 'account.passkey_'))
        ORDER BY at DESC, id DESC LIMIT $FactorLimit
      """.query[(String, Instant)].run().toList.map(Factor.apply.tupled)
      val high = sql"""
        SELECT $EventColumns FROM audit_event
        WHERE user_id = $userId AND at >= $from AND at <= $to AND severity = 'high'
        ORDER BY at DESC, id DESC LIMIT $TopLimit
      """.query[EventRow].run().toList.map(_.toEvent)
      Summary(byKind, byClient, ips, userAgents, sessions, factors, high)

object PostgresAuditEventRepository:
  /** Events the database refused one by one, after a batch it refused for what is in it: lost, and the only way an event is lost that
    * the writer does not itself count. Alert on it increasing. */
  val rejected = Metric.counter("audit_events_rejected_total")

  /** What the table can hold of an event whatever the caller sent: a text column and a JSON string refuse U+0000, and `ip` is an address
    * or nothing. The rest of what the caller sent is kept as it is. */
  def sanitized(event: AuditEvent): AuditEvent =
    def text(value: String): String = value.replace('\u0000', '\uFFFD')
    def json(value: Json): Json = value match
      case Json.Str(s) => Json.Str(text(s))
      case Json.Arr(items) => Json.Arr(items.map(json))
      case Json.Obj(fields) => Json.Obj(fields.map((key, v) => text(key) -> json(v)))
      case other => other
    event.copy(
      tenantId = event.tenantId.map(text),
      nodeId = text(event.nodeId),
      reason = event.reason.map(text),
      actorId = event.actorId.map(text),
      clientId = event.clientId.map(text),
      sessionId = event.sessionId.map(text),
      ip = event.ip.filter(literal => IpAddress.normalize(literal).isDefined),
      userAgent = event.userAgent.map(text),
      traceId = event.traceId.map(text),
      targetType = event.targetType.map(text),
      targetId = event.targetId.map(text),
      details = Json.Obj(event.details.fields.map((key, v) => text(key) -> json(v))),
    )

  /** The SQLSTATEs anywhere in the chain of causes and chained exceptions. */
  private[audit] def sqlStates(error: Throwable): List[String] =
    def chain(e: Throwable): List[Throwable] =
      if e == null then Nil
      else
        val next = e match
          case sql: java.sql.SQLException => Option(sql.getNextException).toList.flatMap(chain)
          case _ => Nil
        e :: chain(e.getCause) ::: next
    chain(error).collect { case sql: java.sql.SQLException if sql.getSQLState != null => sql.getSQLState }

  /** SQLSTATE class 22 in that chain: a value the column cannot hold, not a database that cannot be reached. */
  private[audit] def isDataException(error: Throwable): Boolean = sqlStates(error).exists(_.startsWith("22"))

  private[audit] def sqlState(error: Throwable): Option[String] = sqlStates(error).find(_.startsWith("22"))

  /** The partitions of `audit_event`: the default partition's rows stay, and a delete is marked with the setting the table's trigger
    * looks for. */
  val partitions: DailyPartitions =
    DailyPartitions(
      table = "audit_event",
      defaultRows = DailyPartitions.DefaultRows.LeaveAndReport,
      deleteGuard = Some("versola.audit_maintenance"),
      label = Some("audit"),
    )

  val live: ZLayer[TransactorZIO, Nothing, AuditEventRepository & AuditReadRepository] =
    ZLayer.fromFunction((xa: TransactorZIO) => PostgresAuditEventRepository(xa, partitions)).flatMap: env =>
      ZLayer.succeedEnvironment(
        zio.ZEnvironment[AuditEventRepository, AuditReadRepository](env.get, env.get),
      )

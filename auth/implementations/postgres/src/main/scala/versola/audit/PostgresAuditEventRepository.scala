package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.audit.AuditReadRepository.*
import versola.util.audit.*
import versola.util.partitions.{PartitionReport, TableOwnership}
import versola.util.postgres.{AuditInsert, BasicCodecs, DailyPartitions}
import zio.json.*
import zio.json.ast.Json
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

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private def timed()(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${statementTimeout.toMillis}ms'")}".update.run()

  /** The batch in one round trip, by the statement every service shares ([[AuditInsert]]). */
  override def insertBatch(events: List[AuditEvent]): Task[Unit] =
    if events.isEmpty then ZIO.unit
    else
      xa.transactMeasured("insert-audit-events"):
        timed()
        AuditInsert.insertBatch(events)
      .unit

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

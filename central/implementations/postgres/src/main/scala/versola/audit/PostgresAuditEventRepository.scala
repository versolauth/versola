package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.audit.{CentralAudit, CentralAuditRepository}
import versola.util.audit.*
import versola.util.partitions.{PartitionReport, TableOwnership}
import versola.util.postgres.{AuditInsert, BasicCodecs, DailyPartitions}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps}
import zio.{Task, ZEnvironment, ZIO, ZLayer, durationInt}

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

/** `central_audit_event` of central (V1034). The partitions are [[DailyPartitions]]'; a day whose rows sit in the default partition is
  * reported and not attached, since the audit log's rows are never moved (an insert-only replication would see them twice), and
  * the default partition is pruned under `versola.audit_maintenance`, which the table's trigger asks for. */
class PostgresAuditEventRepository(xa: TransactorZIO) extends CentralAuditRepository, BasicCodecs:
  import PostgresAuditEventRepository.*

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private val partitions = DailyPartitions(
    table = CentralAudit.Table,
    defaultRows = DailyPartitions.DefaultRows.LeaveAndReport,
    deleteGuard = Some("versola.audit_maintenance"),
    label = Some("audit-events"),
  )

  private def timed()(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${StatementTimeoutMillis}ms'")}".update.run()

  override def insertBatch(events: List[AuditEvent]): Task[Unit] =
    if events.isEmpty then ZIO.unit
    else
      xa.transactMeasured("insert-audit-events"):
        timed()
        AuditInsert.insertBatch(events, CentralAudit.Table)
      .unit

  override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
    partitions.maintain(xa, from, days, keepFrom)

  override def partitionDaysAhead(today: LocalDate): Task[Int] = partitions.partitionDaysAhead(xa, today)

  override def pruneDefault(before: Instant): Task[Long] = partitions.pruneDefault(xa, before)

  override def ownership: Task[TableOwnership] = partitions.ownership(xa)

  override def userEvents(
      userId: UUID,
      from: Instant,
      until: Instant,
      before: Option[CentralAuditRepository.Key],
      limit: Int,
  ): Task[List[AuditEvent]] =
    val lower = from.atOffset(ZoneOffset.UTC)
    val upper = until.atOffset(ZoneOffset.UTC)
    xa.connectMeasured("find-audit-events-of-user"):
      val rows = before match
        case None =>
          sql"""
            SELECT id, at, tenant_id, source, node_id, kind, outcome, reason, severity, actor_type, actor_id, user_id, client_id,
                   session_id, host(ip), user_agent, trace_id, target_type, target_id, details::text
            FROM central_audit_event
            WHERE user_id = $userId AND at >= $lower AND at < $upper
            ORDER BY at DESC, id DESC
            LIMIT $limit
          """.query[Row].run()
        case Some(key) =>
          val keyAt = key.at.atOffset(ZoneOffset.UTC)
          sql"""
            SELECT id, at, tenant_id, source, node_id, kind, outcome, reason, severity, actor_type, actor_id, user_id, client_id,
                   session_id, host(ip), user_agent, trace_id, target_type, target_id, details::text
            FROM central_audit_event
            WHERE user_id = $userId AND at >= $lower AND at < $upper AND (at, id) < ($keyAt, ${key.id})
            ORDER BY at DESC, id DESC
            LIMIT $limit
          """.query[Row].run()
      rows.toList
    .flatMap(rows => ZIO.foreach(rows)(_.toEvent))

  override def adminActions(userId: UUID, from: Instant, until: Instant, limit: Int): Task[List[AuditEvent]] =
    val lower = from.atOffset(ZoneOffset.UTC)
    val upper = until.atOffset(ZoneOffset.UTC)
    val viewed = CentralAudit.AdminUserViewed.value
    val queried = CentralAudit.AdminAuditQueried.value
    xa.connectMeasured("find-admin-actions-on-user"):
      sql"""
        SELECT id, at, tenant_id, source, node_id, kind, outcome, reason, severity, actor_type, actor_id, user_id, client_id,
               session_id, host(ip), user_agent, trace_id, target_type, target_id, details::text
        FROM central_audit_event
        WHERE user_id = $userId AND at >= $lower AND at < $upper
          AND kind LIKE 'admin.%' AND kind NOT IN ($viewed, $queried)
        ORDER BY at DESC, id DESC
        LIMIT $limit
      """.query[Row].run().toList
    .flatMap(rows => ZIO.foreach(rows)(_.toEvent))

object PostgresAuditEventRepository:

  /** The longest one statement of the writer, the report or the maintenance may take. */
  private val StatementTimeoutMillis = 30.seconds.toMillis

  val live: ZLayer[TransactorZIO, Nothing, CentralAuditRepository & AuditEventRepository] =
    ZLayer.fromZIOEnvironment:
      ZIO.service[TransactorZIO].map: xa =>
        val repository = PostgresAuditEventRepository(xa)
        ZEnvironment[CentralAuditRepository, AuditEventRepository](repository, repository)

  /** A row as it is read: the closed sets as text, the payload as JSON text; [[toEvent]] makes the event of it. */
  private case class Row(
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

    def toEvent: Task[AuditEvent] =
      ZIO.attempt:
        def named[A](values: Array[A], what: String, name: String): A =
          values.find(_.toString == name).getOrElse(throw IllegalStateException(s"unknown $what '$name' in central_audit_event $id"))
        AuditEvent(
          id = id,
          at = at,
          tenantId = tenantId,
          source = AuditSource(source),
          nodeId = nodeId,
          kind = AuditKind(kind),
          outcome = named(AuditOutcome.values, "outcome", outcome),
          reason = reason,
          severity = named(AuditSeverity.values, "severity", severity),
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

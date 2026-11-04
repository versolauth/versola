package versola.util.postgres

import com.augustnagro.magnum.*
import versola.util.audit.{AuditEvent, AuditSanitizer}
import zio.json.*

import java.time.Instant

/** The one statement that writes an audit event into the service's own `audit_event` table. A service's repository calls it, so
  * that the column list is in this place and no other: [[insert]] inside the transaction of the change the event describes, which
  * then commits or rolls back with it, and [[insertBatch]] for the queued events the writer takes a batch at a time. The
  * transaction, and the statement timeout inside it, are the caller's: this adds one INSERT to whatever transaction it is in and
  * never commits.
  *
  * The text of an event is made storable here as well ([[AuditSanitizer]]), since what reaches a repository need not have come
  * through `AuditEvents.build`, and one row the table refuses would fail the whole transaction it is in. */
object AuditInsert extends BasicCodecs:

  private given DbCodec[Instant] = DbCodec.InstantCodec

  /** One event, in the transaction the caller has open. */
  def insert(event: AuditEvent)(using DbCon): Unit =
    statement(AuditSanitizer(event)).run()
    ()

  /** Events in one round trip; nothing for an empty list. */
  def insertBatch(events: List[AuditEvent])(using DbCon): Unit =
    if events.nonEmpty then
      batchUpdate(events.map(AuditSanitizer.apply))(statement)
      ()

  /** The columns of an event, in the order [[statement]] names their values. */
  private val Columns = SqlLiteral(
    "id, at, tenant_id, source, node_id, kind, outcome, reason, severity, actor_type, actor_id, user_id, client_id, session_id, " +
      "ip, user_agent, trace_id, target_type, target_id, details",
  )

  // `ip` and `details` are text bound to the column's type: INET and JSONB have no driver type of their own here.
  private def statement(event: AuditEvent): Update =
    sql"""
      INSERT INTO audit_event ($Columns)
      VALUES (
        ${event.id}, ${event.at}, ${event.tenantId}, ${event.source.value}, ${event.nodeId}, ${event.kind.value},
        ${event.outcome.toString}, ${event.reason}, ${event.severity.toString}, ${event.actorType.value}, ${event.actorId},
        ${event.userId}, ${event.clientId}, ${event.sessionId}, CAST(${event.ip} AS inet), ${event.userAgent}, ${event.traceId},
        ${event.targetType}, ${event.targetId}, CAST(${event.details.toJson} AS jsonb)
      )
    """.update

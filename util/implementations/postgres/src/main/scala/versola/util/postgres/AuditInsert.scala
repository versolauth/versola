package versola.util.postgres

import com.augustnagro.magnum.*
import versola.util.audit.AuditEvent
import zio.json.*

import java.time.Instant

/** The one statement that writes audit events into the service's own `audit_event` table, so that its column list and its
  * conflict rule are in this place and not copied into every service's repository. A repository's `insertBatch` calls it inside
  * its own transaction, which is also where the statement timeout is set.
  *
  * An event is written once however often it is offered: the row is keyed by `(id, at)` and a second insert of the same event does
  * nothing. The writer offers a batch again when it does not know whether the first attempt committed, and the conflict rule makes
  * the second attempt write the same rows. The first insert wins; two different events are told apart by their ids, which are UUIDv7.
  *
  * What an event holds is already storable: the writer's events come from `AuditEvents.record`, which sanitises them. */
object AuditInsert extends BasicCodecs:

  private given DbCodec[Instant] = DbCodec.InstantCodec

  /** Events in one round trip, in the transaction the caller has open; nothing for an empty list. */
  def insertBatch(events: List[AuditEvent])(using DbCon): Unit =
    if events.nonEmpty then
      batchUpdate(events)(statement)
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
      ON CONFLICT (id, at) DO NOTHING
    """.update

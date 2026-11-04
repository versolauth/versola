package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.audit.{ActorType, AuditDetails, AuditDraft, AuditEvent, AuditEvents, AuditKind, AuditOutcome, AuditSeverity, AuditSource}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID

/** What the specs of the repositories that insert an audit event in the transaction of their change need: an event to hand them, and a
  * way to say whether it was stored. Rows are told apart by the id of the event, which every `event` makes new, so that a spec does
  * not depend on what another left in `audit_event` (the table is append-only: a spec cannot clear it).
  *
  * A repository is handed the event twice to see its change rolled back with it: the second insert is a duplicate key. */
object AuditProbe:

  /** A fresh event of `kind`, as the service builds it (here from the no-op `AuditEvents`, which stamps a random id and the clock's time). */
  def event(
      kind: AuditKind = AuthAuditKinds.AccountClaimsUpdated,
      userId: Option[UUID] = None,
      details: AuditDetails = AuditDetails.empty,
  ): UIO[AuditEvent] =
    // The id is random for real: the test clock's random gives the same ids to every case, and the table keeps what the cases before left.
    AuditEvents.noop.build(AuditDraft(kind = kind, outcome = AuditOutcome.success, userId = userId, details = details))
      .map(_.copy(id = UUID.randomUUID()))

  /** An event as a value, for a spec of a service that hands it to a stub repository and never stores it. */
  def sample(kind: AuditKind = AuthAuditKinds.AccountClaimsUpdated, userId: Option[UUID] = None): AuditEvent =
    AuditEvent(
      id = UUID.randomUUID(),
      at = Instant.EPOCH,
      tenantId = None,
      source = AuditSource("auth"),
      nodeId = "spec",
      kind = kind,
      outcome = AuditOutcome.success,
      reason = None,
      severity = AuditSeverity.info,
      actorType = ActorType.system,
      actorId = None,
      userId = userId,
      clientId = None,
      sessionId = None,
      ip = None,
      userAgent = None,
      traceId = None,
      targetType = None,
      targetId = None,
      details = zio.json.ast.Json.Obj(),
    )

  /** Rows of `audit_event` of this event's id: 1 when it was stored, 0 when it was not. */
  def stored(event: AuditEvent): ZIO[TransactorZIO, Throwable, Long] =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect(sql"SELECT COUNT(*) FROM audit_event WHERE id = ${event.id}".query[Long].run().head)

  /** The rows of `audit_event` that came from a template: they are told apart by the template's id, which a copy per row derives from
    * (see `AuditSupport.forSession`), so a spec reads them by user and kind. */
  def storedFor(kind: AuditKind, userId: UUID): ZIO[TransactorZIO, Throwable, List[(String, Option[String], String)]] =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect:
        sql"""SELECT kind, session_id, details::text FROM audit_event WHERE kind = ${kind.value} AND user_id = $userId ORDER BY id"""
          .query[(String, Option[String], String)].run().toList

  /** One field of the `details` of the row of this event: what the repository filled in, a count or an id. */
  def detail(event: AuditEvent, name: String): ZIO[TransactorZIO, Throwable, Option[Json]] =
    detailsOf(event).map(_.flatMap(_.fromJson[Json.Obj].toOption).flatMap(_.fields.toMap.get(name)))

  /** The user and the session the row of this event names. */
  def subject(event: AuditEvent): ZIO[TransactorZIO, Throwable, Option[(Option[UUID], Option[String])]] =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect(sql"SELECT user_id, session_id FROM audit_event WHERE id = ${event.id}".query[(Option[UUID], Option[String])].run().headOption)

  /** The `details` of the row of this event, as text. */
  def detailsOf(event: AuditEvent): ZIO[TransactorZIO, Throwable, Option[String]] =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect(sql"SELECT details::text FROM audit_event WHERE id = ${event.id}".query[String].run().headOption)

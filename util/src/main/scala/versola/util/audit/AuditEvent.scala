package versola.util.audit

import zio.json.ast.Json
import zio.json.{JsonCodec, JsonEncoder}

import java.time.Instant
import java.util.UUID

/** One row of the audit log, as it is stored and as it is served (the wire form of auth's per-user endpoints and the replica's
  * view): the field names are the JSON member names. `details` is the kind's own payload ([[AuditDetails]]). */
case class AuditEvent(
    id: UUID,
    at: Instant,
    tenantId: Option[String],
    source: AuditSource,
    nodeId: String,
    kind: AuditKind,
    outcome: AuditOutcome,
    reason: Option[String],
    severity: AuditSeverity,
    actorType: ActorType,
    actorId: Option[String],
    userId: Option[UUID],
    clientId: Option[String],
    sessionId: Option[String],
    ip: Option[String],
    userAgent: Option[String],
    traceId: Option[String],
    targetType: Option[String],
    targetId: Option[String],
    details: Json.Obj,
) derives JsonCodec

/** What a call site gives; the rest ([[AuditEvent]]'s id, time, severity and request context) is filled in by [[AuditEvents]].
  *
  * @param actor   `None`: the `user` of the request's context when it has one, else `system`
  * @param userId  `None`: the request's context ([[AuditContext]], then the `auth` log context)
  * @param target  `(type, id)`; `None`: what [[AuditContext.setTarget]] set, if anything
  */
case class AuditDraft(
    kind: AuditKind,
    outcome: AuditOutcome,
    reason: Option[String] = None,
    actor: Option[(ActorType, Option[String])] = None,
    userId: Option[UUID] = None,
    target: Option[(String, String)] = None,
    details: AuditDetails = AuditDetails.empty,
)

/** A kind's payload: one case class per kind in the service that emits it, which `derives JsonEncoder` and renders itself with
  * [[AuditDetails.render]]. A trait rather than a JSON object at the call site, so that a `Secret` cannot get in: there is no
  * encoder for it. */
trait AuditDetails:
  def toJson: Json.Obj

object AuditDetails:
  object empty extends AuditDetails:
    override def toJson: Json.Obj = Json.Obj()

  /** The JSON object of a case class; a value that does not render as an object (it cannot, for a case class) is an empty one. */
  def render[A: JsonEncoder](details: A): Json.Obj =
    JsonEncoder[A].toJsonAST(details).toOption match
      case Some(obj: Json.Obj) => obj
      case _ => Json.Obj()

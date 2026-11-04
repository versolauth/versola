package versola.util.audit

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

/** The words an audit event is made of. They are strings or small enums that the core only carries: which kinds exist, which
  * sources record and which actors act is for the services to say (see [[AuditCatalog]]), not for this module, which no service
  * is named in. */

/** A kind of audit event, a dotted name such as `token.issued`. A string and not an enum, so that a service can store what another
  * one sends without knowing its kinds. The services own their kinds: each lists them in an [[AuditCatalog]], and a spec of the
  * service asserts that everything it emits is listed. */
opaque type AuditKind = String

object AuditKind:
  /** Unchecked: the service's own constants are made with it, and what was stored or received is read with it. */
  def apply(value: String): AuditKind = value

  given JsonCodec[AuditKind] = JsonCodec.string

  extension (kind: AuditKind) def value: String = kind

/** The service that recorded an event: the `source` column and a metric label, so a closed set the service
  * defines once and passes to [[AuditEvents.live]]. */
opaque type AuditSource = String

object AuditSource:
  def apply(name: String): AuditSource = name

  given JsonCodec[AuditSource] = JsonCodec.string

  extension (source: AuditSource) def value: String = source

/** Who did it: a user, an admin, a client, a service, the system. The core fills `user` and `system` in itself (a request with a
  * user in its context, and one without); a service defines the others it needs with `ActorType("...")`. */
opaque type ActorType = String

object ActorType:
  def apply(name: String): ActorType = name

  val user: ActorType = "user"
  val system: ActorType = "system"

  given JsonCodec[ActorType] = JsonCodec.string

  extension (actorType: ActorType) def value: String = actorType

enum AuditOutcome:
  case success, failure, denied

object AuditOutcome:
  given JsonCodec[AuditOutcome] = AuditEnums.codec(values, "audit outcome")

enum AuditSeverity:
  case info, notice, high

object AuditSeverity:
  given JsonCodec[AuditSeverity] = AuditEnums.codec(values, "audit severity")

private object AuditEnums:
  /** An enum as its case name, which is also what the `audit_event` columns hold. */
  def codec[A](values: Array[A], what: String): JsonCodec[A] =
    JsonCodec(
      JsonEncoder[String].contramap(_.toString),
      JsonDecoder[String].mapOrFail(name => values.find(_.toString == name).toRight(s"unknown $what: $name")),
    )

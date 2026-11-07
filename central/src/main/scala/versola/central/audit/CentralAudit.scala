package versola.central.audit

import versola.util.audit.{ActorType, AuditCatalog, AuditDetails, AuditDraft, AuditEvents, AuditKind, AuditOutcome, AuditSource}
import zio.metrics.{Metric, MetricLabel}
import zio.{UIO, ZIO}

/** What central says about its own audit events (#469): the source it records as, the actors that act through it, the kinds it
  * emits and how serious each is. The core (`versola.util.audit`) takes this as the service's [[AuditCatalog]] and rates and
  * counts every event by it.
  *
  * An administrator's action is `admin.<resource>.<operation>`: `resource` from the closed set below (one name per kind of thing
  * the console manages), `operation` `create`, `update`, `delete`, or `action:<name>` for a route that does something else to a
  * resource (`action:rotate-secret`). The kind is built from those two, so the set of kinds is closed and a metric label made of it
  * is bounded. */
object CentralAudit:

  val Source: AuditSource = AuditSource("central")

  /** The table of central's audit log: its own name, since every service migrates into the same database in a local or CI stack. */
  val Table = "central_audit_event"

  /** Who acted: the administrator edge identified, a client that called the API as itself (a provisioning client), or a caller no
    * actor header named (a service). `user` and `system` are the core's. */
  object Actors:
    val Admin: ActorType = ActorType("admin")
    val Client: ActorType = ActorType("client")
    val Service: ActorType = ActorType("service")

    /** An edge that asked central for a certificate on the mutual-TLS listener or for one of its clients. */
    val Edge: ActorType = ActorType("edge")

  // explicit events: the ones that are not `admin.<resource>.<operation>`
  val AdminUserViewed: AuditKind = AuditKind("admin.user.viewed")
  val AdminSecretRevealed: AuditKind = AuditKind("admin.secret.revealed")
  val AdminAuditQueried: AuditKind = AuditKind("admin.audit.queried")
  val SystemBootstrap: AuditKind = AuditKind("system.bootstrap")

  // certificates central issues or tracks, recorded where the record of the issuance or the enrolment is written; auth records
  // the refused EST attempts
  val CertEnrolled: AuditKind = AuditKind("cert.enrolled")
  val CertRenewed: AuditKind = AuditKind("cert.renewed")
  val CertRevoked: AuditKind = AuditKind("cert.revoked")

  /** An event of a certificate flow: no administrator's request starts it, its actor is the holder of the certificate (a client, an
    * edge) or the system, and its target is the client or the edge the certificate is of. */
  def certificate(events: AuditEvents, kind: AuditKind, actor: ActorType, actorId: Option[String], target: (String, String)): UIO[Unit] =
    events.recordBestEffort(
      AuditDraft(
        kind = kind,
        outcome = AuditOutcome.success,
        reason = None,
        actor = Some((actor, actorId)),
        userId = None,
        target = Some(target),
        details = AuditDetails.empty,
      ),
    )

  private val actorMissing = Metric.counter("audit_actor_missing_total")

  /** A call of an administrator's API that edge did not say the caller of (`absent`), or whose caller it named is no user or client
    * (`invalid`): recorded as a service's. Nothing is counted for a call that has an actor. A call whose address is none is counted
    * (`invalid_ip`) and recorded without one. A call that states an actor no edge signed is counted (`unsigned`, or `invalid_signature` for
    * a signature that does not verify) and is a service's. */
  def actorMissing(reason: Option[String]): UIO[Unit] =
    ZIO.foreachDiscard(reason)(reason => actorMissing.tagged(Set(MetricLabel("reason", reason))).increment)

  private val actorRefused = Metric.counter("audit_actor_refused_total")

  /** A call of an administrator's route refused because `audit.require-actor` is on and it named no valid actor: `absent` (edge added
    * none), `invalid` (what it named is no user and no client), `unsigned` or `invalid_signature`. */
  def actorRefused(reason: String): UIO[Unit] = actorRefused.tagged(Set(MetricLabel("reason", reason))).increment

  private val targetMissingCounter = Metric.counter("audit_event_without_target_total")

  /** A change that succeeded and whose handler did not name its target (or, on a user's resource, the user): its row is not found by the
    * target index or the per-user report. The kind is one of the closed set of kinds. */
  def targetMissing(kind: AuditKind): UIO[Unit] = targetMissingCounter.tagged(Set(MetricLabel("kind", kind.value))).increment

  /** The resources an administrator's action can be on (design §4.2): what the routes of [[AuditRoutes]] map to. */
  val resources: Set[String] = Set(
    "tenant",
    "client",
    "client-secret",
    "client-est",
    "edge",
    "edge-group",
    "edge-policy",
    "edge-key",
    "resource",
    "resource-secret",
    "scope",
    "permission",
    "role",
    "auth-request-preset",
    "authorization-detail-type",
    "user",
    "user-claims",
    "user-roles",
    "user-session",
    "user-user-agent",
    "user-refresh-token",
    "user-limits",
    "user-passkey",
    "user-password",
    "otp-template",
    "challenge-settings",
    "form",
    "locale",
    "theme",
    "jwks",
    "server-metadata",
    "system-settings",
    "delivery-settings",
    "antifraud-list",
    "antifraud-settings",
    "antifraud-counters",
  )

  val AdminCreate = "create"
  val AdminUpdate = "update"
  val AdminDelete = "delete"
  val AdminActionPrefix = "action:"

  private val actionName = raw"[a-z0-9-]+".r

  def isAdminOperation(operation: String): Boolean =
    operation == AdminCreate || operation == AdminUpdate || operation == AdminDelete ||
      (operation.startsWith(AdminActionPrefix) && actionName.matches(operation.stripPrefix(AdminActionPrefix)))

  /** `admin.<resource>.<operation>`; both parts from the closed sets above, which `AuditRoutesSpec` asserts of every route. */
  def admin(resource: String, operation: String): AuditKind = AuditKind(s"admin.$resource.$operation")

  /** The resource of a built admin kind; `None` for any other kind, the explicit `admin.*` ones included. */
  def adminResource(kind: AuditKind): Option[String] =
    kind.value match
      case s"admin.$resource.$operation" if resources.contains(resource) && isAdminOperation(operation) => Some(resource)
      case _ => None

  /** An action on these is `high` whatever its outcome: each is a way to take over an account or a client, or to change the keys of
    * the system. */
  private val highResources = Set("jwks", "client-secret", "resource-secret", "role", "permission", "edge-key", "user-password", "user-roles")

  private val explicit = Set(AdminUserViewed, AdminSecretRevealed, AdminAuditQueried, SystemBootstrap, CertEnrolled, CertRenewed, CertRevoked)

  val catalog: AuditCatalog = new AuditCatalog:
    override def isKnown(kind: AuditKind): Boolean = explicit.contains(kind) || adminResource(kind).isDefined

    override def isHigh(kind: AuditKind): Boolean = kind == CertRevoked || adminResource(kind).exists(highResources.contains)

    /** A secret that was handed back, a look at a person: worth a second look even when it succeeded. */
    override def isNotice(kind: AuditKind): Boolean = kind == AdminSecretRevealed || kind == AdminUserViewed

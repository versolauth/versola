package versola.central.audit

import versola.util.audit.{ActorType, AuditCatalog, AuditDraft, AuditEvents, AuditKind, AuditOutcome, AuditSource, CertAuditDetails}
import zio.metrics.{Metric, MetricLabel}
import zio.{UIO, ZIO}

import javax.security.auth.x500.X500Principal

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

  /** Why a certificate was not issued, as the `reason` of a `denied` or `failure` event: a closed set of codes, never the text of an
    * error, a token, a request or what the CA answered. A refusal (`denied`) is a rule saying no, which retrying does not change; a
    * failure is something that did not work, which may. */
  object CertReasons:
    // denied
    val TokenInvalid = "token_invalid"
    val EdgeUnknown = "edge_unknown"
    val RequestMismatch = "request_mismatch"
    val ChainLimit = "chain_limit"
    val NotEnrolled = "not_enrolled"
    val NotServed = "not_served"
    val NoMtlsAuth = "no_mtls_auth"
    val InvalidSubject = "invalid_subject"
    val InvalidCredentials = "invalid_credentials"
    val BadRequest = "bad_request"
    val Forbidden = "forbidden"
    val ChainFork = "chain_fork"

    // failure
    val CaNotConfigured = "ca_not_configured"
    val CaExpired = "ca_expired"
    val IssuerUnreachable = "issuer_unreachable"
    val IssuerRefused = "issuer_refused"
    val IssuerTimeout = "issuer_timeout"
    val IssuerInvalidAnswer = "issuer_invalid_answer"
    val IssuerError = "issuer_error"
    val CertificateRejected = "certificate_rejected"
    val ClientMissing = "client_missing"
    val ClientWithoutMtlsAuth = "client_without_mtls_auth"
    val Unexpected = "unexpected"

    val all: Set[String] = Set(
      TokenInvalid,
      EdgeUnknown,
      RequestMismatch,
      ChainLimit,
      NotEnrolled,
      NotServed,
      NoMtlsAuth,
      InvalidSubject,
      InvalidCredentials,
      BadRequest,
      Forbidden,
      ChainFork,
      CaNotConfigured,
      CaExpired,
      IssuerUnreachable,
      IssuerRefused,
      IssuerTimeout,
      IssuerInvalidAnswer,
      IssuerError,
      CertificateRejected,
      ClientMissing,
      ClientWithoutMtlsAuth,
      Unexpected,
    )

  /** Why a certificate was not issued: `outcome` is `denied` for a rule that said no and `failure` for something that did not work,
    * `reason` one of [[CertReasons]], `error` what the caller is answered with (which is not the audit reason) and `subject` the one
    * the attempt was for, where it got that far. */
  case class CertFailure[+E](outcome: AuditOutcome, reason: String, error: E, subject: Option[X500Principal])

  object CertFailure:
    /** A failure nothing classified: the caller is answered with the error itself. */
    def unexpected[E](error: E): CertFailure[E] = CertFailure(AuditOutcome.failure, CertReasons.Unexpected, error, None)

  /** An event of a certificate flow: no administrator's request starts it, its actor is the holder of the certificate (a client, an
    * edge) or the system, and its target is the client or the edge the certificate is of. `details` says which certificate it is: the
    * serial, the end of the validity and the subject (see [[CertAuditDetails]]); for an attempt that issued nothing, `outcome` is
    * `denied` or `failure` and `reason` one of [[CertReasons]]. */
  def certificate(
      events: AuditEvents,
      kind: AuditKind,
      actor: ActorType,
      actorId: Option[String],
      target: (String, String),
      outcome: AuditOutcome,
      reason: Option[String],
      details: CertAuditDetails,
  ): UIO[Unit] =
    events.recordBestEffort(
      AuditDraft(
        kind = kind,
        outcome = outcome,
        reason = reason,
        actor = Some((actor, actorId)),
        userId = None,
        target = Some(target),
        details = details,
      ),
    )

  private val actorMissing = Metric.counter("audit_actor_missing_total")

  /** A call of an administrator's API that edge did not say the caller of (`absent`), or whose caller it named is no user or client
    * (`invalid`): recorded as a service's. Nothing is counted for a call that has an actor. A call whose address is none is counted
    * (`invalid_ip`) and recorded without one. */
  def actorMissing(reason: Option[String]): UIO[Unit] =
    ZIO.foreachDiscard(reason)(reason => actorMissing.tagged(Set(MetricLabel("reason", reason))).increment)

  private val actorRefused = Metric.counter("audit_actor_refused_total")

  /** A call of an administrator's route refused because it named no valid actor: `absent` (edge added
    * none) or `invalid` (what it named is no user and no client). */
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

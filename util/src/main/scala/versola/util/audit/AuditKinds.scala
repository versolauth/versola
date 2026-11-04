package versola.util.audit

import zio.json.JsonCodec

/** The service an event was recorded by. */
enum AuditSource:
  case auth, central, edge

enum AuditOutcome:
  case success, failure, denied

enum AuditSeverity:
  case info, notice, high

enum ActorType:
  case user, admin, client, edge, service, system

/** A kind of audit event: a closed vocabulary ([[AuditKinds]]). A string and not an enum, so that central can store what another
  * service sends later without knowing every kind; what a service of this codebase emits is checked against the vocabulary by
  * a spec. */
opaque type AuditKind = String

object AuditKind:
  /** Unchecked: for what was stored or received, not for what a service emits (use the constants of [[AuditKinds]]). */
  def apply(value: String): AuditKind = value

  given JsonCodec[AuditKind] = JsonCodec.string

  extension (kind: AuditKind) def value: String = kind

object AuditKinds:
  // auth
  val LoginStep: AuditKind = AuditKind("login.step")
  val LoginCompleted: AuditKind = AuditKind("login.completed")
  val AuthorizeRequest: AuditKind = AuditKind("authorize.request")
  val ParRequest: AuditKind = AuditKind("par.request")
  val ConsentGranted: AuditKind = AuditKind("consent.granted")
  val ConsentDenied: AuditKind = AuditKind("consent.denied")
  val ConsentRevoked: AuditKind = AuditKind("consent.revoked")
  val TokenIssued: AuditKind = AuditKind("token.issued")
  val TokenRefused: AuditKind = AuditKind("token.refused")
  val TokenReuseDetected: AuditKind = AuditKind("token.reuse_detected")
  val TokenRevoked: AuditKind = AuditKind("token.revoked")
  val ClientAuthFailed: AuditKind = AuditKind("client.auth_failed")
  val SessionEnded: AuditKind = AuditKind("session.ended")
  val AccountRegistered: AuditKind = AuditKind("account.registered")
  val AccountPasswordChanged: AuditKind = AuditKind("account.password_changed")
  val AccountPasswordSet: AuditKind = AuditKind("account.password_set")
  val AccountPasswordReset: AuditKind = AuditKind("account.password_reset")
  val AccountPasskeyEnrolled: AuditKind = AuditKind("account.passkey_enrolled")
  val AccountPasskeyRemoved: AuditKind = AuditKind("account.passkey_removed")
  val AccountClaimsUpdated: AuditKind = AuditKind("account.claims_updated")
  val AccountLimitsReset: AuditKind = AuditKind("account.limits_reset")
  val CertEnrolled: AuditKind = AuditKind("cert.enrolled")
  val CertRenewed: AuditKind = AuditKind("cert.renewed")
  val CertRevoked: AuditKind = AuditKind("cert.revoked")
  // central: `admin.<resource>.<operation>` is built by [[admin]]; these are the explicit ones
  val AdminUserViewed: AuditKind = AuditKind("admin.user.viewed")
  val AdminSecretRevealed: AuditKind = AuditKind("admin.secret.revealed")
  val AdminAuditQueried: AuditKind = AuditKind("admin.audit.queried")
  val AuditConfigChanged: AuditKind = AuditKind("audit.config.changed")
  val SystemBootstrap: AuditKind = AuditKind("system.bootstrap")

  /** The resources an admin action can be on: what central's routes map to, one name per kind of thing the console manages. */
  val adminResources: Set[String] = Set(
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

  /** The operations of an admin action: one per method, or `action:<name>` for a route that does something to a resource
    * (`action:rotate-secret`, `action:revoke`, ...), the name being the route's last segment. */
  val AdminCreate = "create"
  val AdminUpdate = "update"
  val AdminDelete = "delete"
  val AdminActionPrefix = "action:"

  private val actionName = raw"[a-z0-9-]+".r

  def isAdminOperation(operation: String): Boolean =
    operation == AdminCreate || operation == AdminUpdate || operation == AdminDelete ||
      (operation.startsWith(AdminActionPrefix) && actionName.matches(operation.stripPrefix(AdminActionPrefix)))

  /** `admin.<resource>.<operation>`; both parts from the closed sets above, which the caller's spec asserts. */
  def admin(resource: String, operation: String): AuditKind = AuditKind(s"admin.$resource.$operation")

  /** The resource of a built admin kind, `None` for any other kind (the explicit `admin.*` ones included). */
  def adminResource(kind: AuditKind): Option[String] =
    kind.value match
      case s"admin.$resource.$operation" if adminResources.contains(resource) && isAdminOperation(operation) => Some(resource)
      case _ => None

  /** Every listed kind; the built admin kinds are not in it, [[isKnown]] covers them. */
  val all: Set[AuditKind] = Set(
    LoginStep,
    LoginCompleted,
    AuthorizeRequest,
    ParRequest,
    ConsentGranted,
    ConsentDenied,
    ConsentRevoked,
    TokenIssued,
    TokenRefused,
    TokenReuseDetected,
    TokenRevoked,
    ClientAuthFailed,
    SessionEnded,
    AccountRegistered,
    AccountPasswordChanged,
    AccountPasswordSet,
    AccountPasswordReset,
    AccountPasskeyEnrolled,
    AccountPasskeyRemoved,
    AccountClaimsUpdated,
    AccountLimitsReset,
    CertEnrolled,
    CertRenewed,
    CertRevoked,
    AdminUserViewed,
    AdminSecretRevealed,
    AdminAuditQueried,
    AuditConfigChanged,
    SystemBootstrap,
  )

  /** Listed, or `admin.<resource>.<operation>` with both parts known. */
  def isKnown(kind: String): Boolean = all.contains(AuditKind(kind)) || adminResource(AuditKind(kind)).isDefined

object AuditSeverity:
  /** Admin actions on these resources are `high` whatever the outcome: each is a way to take over an account or a client. */
  private val highResources = Set("jwks", "client-secret", "resource-secret", "role", "permission", "edge-key", "user-password", "user-roles")

  private val highKinds: Set[AuditKind] = Set(AuditKinds.TokenReuseDetected, AuditKinds.AuditConfigChanged, AuditKinds.CertRevoked)

  private val noticeKinds: Set[AuditKind] = Set(
    AuditKinds.AdminSecretRevealed,
    AuditKinds.AdminUserViewed,
    AuditKinds.AccountPasskeyEnrolled,
    AuditKinds.AccountPasskeyRemoved,
    AuditKinds.AccountPasswordChanged,
    AuditKinds.AccountPasswordSet,
    AuditKinds.AccountPasswordReset,
    AuditKinds.AccountClaimsUpdated,
  )

  /** The severity of a kind: `high` for what is an attack or a change of the keys of the system, `notice` for every refusal and
    * for what changes how an account is reached, `info` for the rest. Fixed here and not at the call site, so that every service
    * rates the same kind the same. */
  def of(kind: AuditKind, outcome: AuditOutcome): AuditSeverity =
    if highKinds.contains(kind) || AuditKinds.adminResource(kind).exists(highResources.contains) then AuditSeverity.high
    else if outcome != AuditOutcome.success || noticeKinds.contains(kind) then AuditSeverity.notice
    else AuditSeverity.info

  given JsonCodec[AuditSeverity] = AuditJson.enumCodec(values, "audit severity")

object AuditSource:
  given JsonCodec[AuditSource] = AuditJson.enumCodec(values, "audit source")

object AuditOutcome:
  given JsonCodec[AuditOutcome] = AuditJson.enumCodec(values, "audit outcome")

object ActorType:
  given JsonCodec[ActorType] = AuditJson.enumCodec(values, "actor type")

private object AuditJson:
  import zio.json.{JsonDecoder, JsonEncoder}

  /** An enum as its case name, which is also what the `audit_event` columns hold. */
  def enumCodec[A](values: Array[A], what: String): JsonCodec[A] =
    JsonCodec(
      JsonEncoder[String].contramap(_.toString),
      JsonDecoder[String].mapOrFail(name => values.find(_.toString == name).toRight(s"unknown $what: $name")),
    )

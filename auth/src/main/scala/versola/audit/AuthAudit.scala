package versola.audit

import versola.util.audit.{ActorType, AuditCatalog, AuditKind, AuditSource}

/** What auth says about its own audit events: the kinds it emits, who acts, and how serious each kind is. The core takes the catalog and
  * applies it to every event, so a kind is rated the same wherever it is recorded and the `kind` label of the metrics is a closed set. */
object AuthAuditKinds:
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

  /** The effects of what an administrator decided in central (central writes the decision): the account removed, its roles changed. */
  val AccountDeleted: AuditKind = AuditKind("account.deleted")

  /** The identifiers of an account (email, phone, login) as central synchronised them: the effect of what central decided, like
    * `account.deleted`. */
  val AccountUpdated: AuditKind = AuditKind("account.updated")
  val AccountRolesUpdated: AuditKind = AuditKind("account.roles_updated")

  /** An administrator's change to the antifraud lists, settings or counters, as central told auth. */
  val AntifraudListChanged: AuditKind = AuditKind("antifraud.list_changed")
  val AntifraudSettingsChanged: AuditKind = AuditKind("antifraud.settings_changed")
  val AntifraudCountersReset: AuditKind = AuditKind("antifraud.counters_reset")
  val CertEnrolled: AuditKind = AuditKind("cert.enrolled")
  val CertRenewed: AuditKind = AuditKind("cert.renewed")

  /** Not emitted by auth: a certificate is revoked in central. Listed so that the vocabulary is the design's. */
  val CertRevoked: AuditKind = AuditKind("cert.revoked")

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
    AccountDeleted,
    AccountUpdated,
    AccountRolesUpdated,
    AntifraudListChanged,
    AntifraudSettingsChanged,
    AntifraudCountersReset,
    CertEnrolled,
    CertRenewed,
    CertRevoked,
  )

/** The actors of auth's events besides `user` and `system`, which the core names itself. */
object AuthActors:
  /** An administrator, as central names them on a call to the internal endpoints. */
  val admin: ActorType = ActorType("admin")

  /** A client, at the endpoints it authenticates to. */
  val client: ActorType = ActorType("client")

  /** A service: central, on a call that no administrator made. */
  val service: ActorType = ActorType("service")

object AuthAudit:
  val source: AuditSource = AuditSource("auth")

  /** `token.reuse_detected` is an attack on a grant and `cert.revoked` a change of who may be trusted: `high`. What changes how an account
    * is reached is at least `notice` when it succeeds too; every failure and denial is `notice` by the core's own rule. */
  val catalog: AuditCatalog = AuditCatalog.of(
    kinds = AuthAuditKinds.all,
    high = Set(AuthAuditKinds.TokenReuseDetected, AuthAuditKinds.CertRevoked),
    notice = Set(
      AuthAuditKinds.AccountPasswordChanged,
      AuthAuditKinds.AccountPasswordSet,
      AuthAuditKinds.AccountPasswordReset,
      AuthAuditKinds.AccountPasskeyEnrolled,
      AuthAuditKinds.AccountPasskeyRemoved,
      AuthAuditKinds.AccountClaimsUpdated,
      AuthAuditKinds.AccountDeleted,
      AuthAuditKinds.AccountUpdated,
      AuthAuditKinds.AccountRolesUpdated,
      AuthAuditKinds.AntifraudListChanged,
      AuthAuditKinds.AntifraudSettingsChanged,
      AuthAuditKinds.AntifraudCountersReset,
    ),
  )

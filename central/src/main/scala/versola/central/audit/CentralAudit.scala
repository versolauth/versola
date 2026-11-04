package versola.central.audit

import versola.util.audit.{ActorType, AuditCatalog, AuditKind, AuditSource}

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

  /** Who acted: the administrator edge identified, a client that called the API as itself (a provisioning client), or a caller no
    * actor header named (a service). `user` and `system` are the core's. */
  object Actors:
    val Admin: ActorType = ActorType("admin")
    val Client: ActorType = ActorType("client")
    val Service: ActorType = ActorType("service")

  // explicit events: the ones that are not `admin.<resource>.<operation>`
  val AdminUserViewed: AuditKind = AuditKind("admin.user.viewed")
  val AdminSecretRevealed: AuditKind = AuditKind("admin.secret.revealed")
  val AdminAuditQueried: AuditKind = AuditKind("admin.audit.queried")
  val SystemBootstrap: AuditKind = AuditKind("system.bootstrap")

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

  private val explicit = Set(AdminUserViewed, AdminSecretRevealed, AdminAuditQueried, SystemBootstrap)

  val catalog: AuditCatalog = new AuditCatalog:
    override def isKnown(kind: AuditKind): Boolean = explicit.contains(kind) || adminResource(kind).isDefined

    override def isHigh(kind: AuditKind): Boolean = adminResource(kind).exists(highResources.contains)

    /** A secret that was handed back, a look at a person: worth a second look even when it succeeded. */
    override def isNotice(kind: AuditKind): Boolean = kind == AdminSecretRevealed || kind == AdminUserViewed

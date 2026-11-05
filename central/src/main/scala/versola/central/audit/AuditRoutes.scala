package versola.central.audit

import versola.util.audit.AuditKind

/** What a route central serves can say to the audit log (#469): the entries a controller declares on a route with
  * [[AuditedRoute]] (see [[Audit]]), which [[AuditHook]] reads from the route it wraps. A route that is not declared is not served:
  * a controller lists only [[AuditedRoute]]s, and `AuditRoutesSpec` checks what the app serves against what is declared.
  *
  * The kinds of a change are `admin.<resource>.<operation>` (design §4.2): `resource` from the closed list in [[CentralAudit]],
  * `operation` is `create` for a `POST`, `update` for a `PUT` or `PATCH`, `delete` for a `DELETE`, and `action:<last segment>`
  * for the routes that do something else to a resource (`rotate-secret`, `generate`, `reset`, ...). A `POST` that stores
  * nothing (a preview, a rendering) is a read.
  */
object AuditRoutes:

  enum Entry:
    /** An administrator's change, recorded whatever its outcome. `revealsSecret`: a success hands a secret back (a new
      * client secret, a private key, a one-time token, a temporary password), which is an `admin.secret.revealed` event of
      * its own on top of the change. */
    case Change(resource: String, operation: String, targetType: String, revealsSecret: Boolean)

    /** A read that is an event in itself (`admin.user.viewed`, `admin.audit.queried`), recorded on success. */
    case Read(kind: AuditKind, targetType: String)

    /** An administrator's read that nobody records. */
    case Unrecorded

    /** Not an administrator's call: auth's syncs and callbacks, the service surface outside production. Auth records what
      * it asks central to do under its own events. */
    case Internal

  val AdminUserViewed: AuditKind = CentralAudit.AdminUserViewed
  val AdminAuditQueried: AuditKind = CentralAudit.AdminAuditQueried

  def change(resource: String, operation: String, targetType: Option[String] = None, revealsSecret: Boolean = false) =
    Entry.Change(
      resource = resource,
      operation = operation,
      targetType = targetType.getOrElse(resource),
      revealsSecret = revealsSecret,
    )

  val create = "create"
  val update = "update"
  val delete = "delete"
  def action(segment: String) = s"action:$segment"

  /** A `user*` resource acts on the user the handler names with `Audit.target`. */
  def user(resource: String, operation: String, revealsSecret: Boolean = false) =
    change(resource = resource, operation = operation, targetType = Some("user"), revealsSecret = revealsSecret)

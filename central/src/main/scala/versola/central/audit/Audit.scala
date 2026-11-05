package versola.central.audit

import versola.central.audit.AuditRoutes.Entry
import versola.util.audit.AuditKind

/** What a controller says about a route to the audit log, with [[AuditedRoute]]: `(Method.POST / "x").audited(Audit.change("client", Audit.create, ...))`. */
object Audit:
  export AuditRoutes.{action, body, change, create, delete, query, update, user}

  /** No target: the call names none (a settings document, a generation of keys). */
  val Nowhere: AuditRoutes.Target = AuditRoutes.Target.Nowhere

  /** A route that stores nothing and shows nothing worth a row: a list, a preview, a rendering. */
  val Unrecorded: Entry = Entry.Unrecorded

  /** Not an administrator's call: auth's and edge's syncs and callbacks, the operator's service surface. */
  val Internal: Entry = Entry.Internal

  val UserViewed: AuditKind = CentralAudit.AdminUserViewed
  val AuditQueried: AuditKind = CentralAudit.AdminAuditQueried

  /** A read that is an event of its own, recorded when it succeeds. */
  def read(kind: AuditKind, target: AuditRoutes.Target, targetType: String): Entry = Entry.Read(kind, target, targetType)

package versola.central.audit

import versola.central.audit.AuditRoutes.Entry
import zio.http.{Handler, Request, Response, Route, RoutePattern}

/** A route and what it means to the audit log, written together: a controller builds the routes it serves only through
  * [[audited]], so there is no route without a declaration, and the declaration is the route's own, not a line in a table that
  * names the route by a string. */
final case class AuditedRoute[-Env](route: Route[Env, Throwable], entry: Entry)

final class AuditedPattern(pattern: RoutePattern[Unit], entry: Entry):
  def ->[Env](handler: Handler[Env, Throwable, Request, Response]): AuditedRoute[Env] = AuditedRoute(pattern -> handler, entry)

extension (pattern: RoutePattern[Unit])
  /** `(Method.POST / "configuration" / "themes").audited(change("theme", create, body("id"))) -> handler { ... }`. */
  def audited(entry: Entry): AuditedPattern = AuditedPattern(pattern, entry)

package versola.central.audit

import versola.util.http.Controller
import zio.http.Routes

/** A controller of central's API: it lists [[AuditedRoute]]s, never a bare route, so that a route cannot be served without saying what
  * it means to the audit log. */
trait AuditedController extends Controller:
  def endpoints: List[AuditedRoute[Env]]

  final def routes: Routes[Env, Throwable] = Routes.fromIterable(endpoints.map(_.route))

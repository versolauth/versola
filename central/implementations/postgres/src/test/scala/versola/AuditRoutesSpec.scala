package versola

import versola.central.audit.AuditRoutes.Entry
import versola.central.audit.CentralAudit
import zio.http.Method
import zio.test.*

/** The audit log of administrators' actions is only complete if every route central serves says what it means to the log (#469): a
  * controller lists only [[versola.central.audit.AuditedRoute]]s, and the app serves exactly what they build. This walks the routes
  * of the app itself and the declarations they were built from, and checks what the declarations say. */
object AuditRoutesSpec extends ZIOSpecDefault:

  private val served: List[(Method, String)] =
    PostgresCentralApp.routes.routes.map(route => (route.routePattern.method, route.routePattern.pathCodec.render)).toList

  private val declared: List[((Method, String), Entry)] =
    PostgresCentralApp.adminEndpoints.map(e => (e.route.routePattern.method, e.route.routePattern.pathCodec.render) -> e.entry)

  private def entries = declared

  def spec = suite("AuditedRoute declarations")(
    test("every route central serves on its main listener carries a declaration, none is declared twice, and none is declared without being served") {
      val declaredRoutes = declared.map(_._1)
      assertTrue(
        served.nonEmpty,
        served.toSet == declaredRoutes.toSet,
        declaredRoutes.distinct.size == declaredRoutes.size,
        served.distinct.size == served.size,
      )
    },
    test("a change is of a resource of the closed set and of an operation the method says, or of an action named by the route's last segment") {
      val wrong = entries.collect:
        case ((method, path), Entry.Change(resource, operation, _, _))
            if !CentralAudit.resources.contains(resource) ||
              !CentralAudit.isAdminOperation(operation) ||
              !CentralAudit.catalog.isKnown(CentralAudit.admin(resource, operation)) ||
              operation != expectedOperation(method, path) =>
          (method, path, resource, operation)
      assertTrue(wrong.isEmpty)
    },
    test("a read is never classified as a change, and a change is never a GET") {
      val gets = entries.collect { case ((Method.GET, path), entry) => path -> entry }
      assertTrue(
        gets.forall:
          case (_, _: Entry.Change) => false
          case _ => true
        ,
        entries.collect { case ((method, _), _: Entry.Read) => method }.forall(_ == Method.GET),
      )
    },
    test("a POST, PUT, PATCH or DELETE of an administrator that is not recorded is a call that stores nothing, and is named here") {
      val unrecorded = entries.collect { case ((method, path), Entry.Unrecorded) if method != Method.GET => method.name -> path }.toSet
      assertTrue(
        unrecorded == Set(
          "POST" -> "/configuration/themes/css",
          "POST" -> "/antifraud/lists/preview",
          "POST" -> "/antifraud/global/lists/preview",
        ),
      )
    },
    test("the routes that hand a secret back are the ones that record an admin.secret.revealed besides") {
      val revealing = entries.collect { case ((method, path), Entry.Change(_, _, _, true)) => method.name -> path }.toSet
      assertTrue(
        revealing == Set(
          "POST" -> "/configuration/clients/web",
          "POST" -> "/configuration/clients/native",
          "POST" -> "/configuration/clients/service",
          "POST" -> "/configuration/clients/rotate-secret",
          "POST" -> "/configuration/clients/est/enrollment-tokens",
          "POST" -> "/configuration/resources",
          "POST" -> "/configuration/resources/rotate-secret",
          "POST" -> "/configuration/edges",
          "POST" -> "/configuration/edges/rotate-key",
          "POST" -> "/configuration/edges/enrollment-tokens",
          "POST" -> "/users/password/reset",
        ),
      )
    },
    test("the two reads that are events of their own are the look at a user and the query of the audit log") {
      val reads = entries.collect { case ((method, path), Entry.Read(kind, targetType)) => (method.name, path, kind.value, targetType) }.toSet
      assertTrue(
        reads == Set(
          ("GET", "/users", "admin.user.viewed", "user"),
          ("GET", "/users/audit", "admin.audit.queried", "user"),
        ),
      )
    },
    test("only auth's callbacks and syncs, the edge-facing surface and the service tooling are internal") {
      val internal = entries.collect { case ((method, path), Entry.Internal) => method.name -> path }.toList
      assertTrue(
        internal.forall((_, path) =>
          path.endsWith("/sync") || path.endsWith("/registry") || path.endsWith("/signing-keys/sync") ||
            path.startsWith("/service/") || path.startsWith("/est/") || path == "/users/registrations",
        ),
        // an administrator's endpoint is never internal: it would go unrecorded
        !internal.exists((_, path) => path.startsWith("/configuration/") && !path.endsWith("sync") && !path.endsWith("registry")),
      )
    },
  )

  /** `create`, `update`, `delete` by the method; an action is named by the last segment of the path. */
  private def expectedOperation(method: Method, path: String): String =
    val last = path.split('/').last
    val actions =
      Set("rotate-secret", "renew", "enrollment-tokens", "extend", "revoke", "dpop", "rotate-key", "active", "default", "generate", "reset", "set")
    if actions.contains(last) then s"action:$last"
    else
      method match
        case Method.POST => "create"
        case Method.PUT | Method.PATCH => "update"
        case Method.DELETE => "delete"
        case other => other.name

package versola.edge

import versola.edge.nativeapp.NativeController
import versola.util.http.Tier
import zio.http.Method
import zio.test.*

object EdgeAdmissionSpec extends ZIOSpecDefault:

  /** Every route the edge serves on its application port, as (method, pattern). */
  private val served: List[(Method, String)] =
    (EdgeController.routes.routes.toList ++ NativeController.routes.routes.toList ++ ServiceController.routes.routes.toList)
      .map(r => (r.routePattern.method, r.routePattern.pathCodec.render))

  def spec = suite("EdgeAdmission")(
    test("every route of the edge is classified on purpose, not by falling to the default") {
      val unnamed = served.filterNot((_, pattern) => EdgeAdmission.isNamed(pattern))
      assertTrue(served.nonEmpty, unnamed.isEmpty)
    },
    test("the tiers: a new login first, then what the user has started, the proxy last, and the system's own never") {
      def tier(m: Method, p: String) = EdgeAdmission.tierOf(m, p)
      assertTrue(
        tier(Method.GET, "/login/{presetId}") == Tier.Start,
        tier(Method.POST, "/native/start/{clientId}") == Tier.Start,
        tier(Method.GET, "/complete") == Tier.Continuation,
        tier(Method.POST, "/native/complete/{clientId}") == Tier.Continuation,
        tier(Method.POST, "/native/token/{clientId}") == Tier.Continuation,
        tier(Method.POST, "/native/revoke/{clientId}") == Tier.Continuation,
        tier(Method.GET, "/logout/{presetId}") == Tier.Continuation,
        tier(Method.GET, "/logout/frontchannel") == Tier.Continuation,
        tier(Method.POST, "/logout/backchannel") == Tier.Never,
        tier(Method.POST, "/service/configuration/sync") == Tier.Never,
        tier(Method.GET, "/permissions/me") == Tier.Critical,
        tier(Method.GET, "/resources/{resourceId}/...") == Tier.Critical,
      )
    },
    test("the patterns the routes really render are the ones the table names") {
      val byPattern = served.map((m, p) => (m.name, p, EdgeAdmission.tierOf(m, p)))
      // printed on failure: the rendered form of each route
      assertTrue(
        byPattern.exists((_, p, t) => p.startsWith("/login/") && t == Tier.Start),
        byPattern.exists((_, p, t) => p.startsWith("/native/start") && t == Tier.Start),
        byPattern.exists((_, p, t) => p == "/logout/backchannel" && t == Tier.Never),
        byPattern.exists((_, p, t) => p.startsWith("/resources/") && t == Tier.Critical),
        byPattern.exists((_, p, t) => p.startsWith("/service/") && t == Tier.Never),
      )
    },
  )

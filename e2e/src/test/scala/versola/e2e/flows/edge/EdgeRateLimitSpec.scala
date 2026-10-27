package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.ast.Json
import zio.test.*

/** The edge's rate limits, end to end: a policy set through central's admin API reaches the running edge by its configuration
  * sync, an `enforce` policy answers `429` with `Retry-After` before the call reaches the upstream, and a policy in `shadow`
  * refuses nothing.
  *
  * The unit specs prove the limiter's arithmetic and the order of the checks on stubs. What only this level shows is the
  * wiring: central's PUT, the stored column, the edge's sync client and the limiter that follows it, the response a real
  * caller gets, and what the upstream is actually handed.
  *
  * The policies are scoped to this spec's own resource (`scope: resource`, keyed on the caller), so no other spec that shares
  * the staged edge is limited, and the set is removed at the end.
  */
object EdgeRateLimitSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & EdgeSession]:

  /** Its own upstream port: a resource's URI is unique, and EdgeProxySpec registers the default stub's. */
  private val UpstreamPort = 9113

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-rate-limit",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(EdgeFixture.Endpoint(name = "list", method = "GET", path = "/items")),
    awaitProxyReady = true,
  )

  /** The edge `gen-env.scala` stages (`edge-default`): its policies are the ones the running edge applies. */
  private val edgeId = "edge-default"

  override val bootstrap
      : ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & EdgeSession] =
    val clients = (E2EConfig.live ++ Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    val fixtured = (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)
    fixtured >+> ZLayer.fromZIO:
      for
        edgeApi <- ZIO.service[EdgeApi]
        authApi <- ZIO.service[OAuthClient]
        f <- ZIO.service[EdgeFixture]
        established <- edgeApi.browserLogin(authApi, f.presetId, f.login, f.password)
      yield established

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock, TestAspect.sequential)

  private def policy(name: String, mode: String, limit: Int) =
    Json.Obj(
      "name" -> Json.Str(name),
      "by" -> Json.Arr(Json.Str("subject")),
      "scope" -> Json.Str("resource"),
      "limits" -> Json.Arr(Json.Obj("limit" -> Json.Num(limit), "windowSeconds" -> Json.Num(3600))),
      "burst" -> Json.Num(limit),
      "mode" -> Json.Str(mode),
    )

  /** `PATCH /configuration/edges/policies` with the `rateLimits` section: the policies, or `null` (none) when there are none. */
  private def setPolicies(policies: Json*) =
    patchSection(if policies.isEmpty then Json.Null else Json.Obj("policies" -> Json.Arr(policies*)))

  private def patchSection(section: Json, query: (String, String)*) =
    ZIO.serviceWithZIO[CentralApi](_.patch("/configuration/edges/policies", Json.Obj("rateLimits" -> section), ("edgeId" -> edgeId) +: query*))

  /** Reloads the edge's configuration, then waits out the limiter's own once-a-second poll of what was loaded. */
  private val reachEdge = ZIO.serviceWithZIO[EdgeApi](_.syncConfiguration) *> ZIO.sleep(3.seconds)

  private def withPolicies[R, A](policies: Json*)(effect: ZIO[R, Throwable, A]): ZIO[R & CentralApi & EdgeApi, Throwable, A] =
    (setPolicies(policies*).flatMap(r =>
      ZIO.fail(RuntimeException(s"PATCH policies: ${r.status} ${r.body}")).unless(r.status == Status.Ok),
    ) *>
      reachEdge *> effect).ensuring((setPolicies() *> reachEdge).ignore)

  def spec = suite("Edge: rate limits")(
    test("central refuses a policy set that does not validate, and says which") {
      for
        refused <- setPolicies(policy("Bad Name", "shadow", 10))
        tooBig <- setPolicies(policy("burst-too-big", "shadow", 10).add("burst", Json.Num(11)))
      yield assertTrue(
        refused.status == Status.BadRequest,
        refused.body.contains("rateLimits") && refused.body.contains("name"),
        tooBig.status == Status.BadRequest,
        tooBig.body.contains("burst"),
      )
    },
    test("central serves the recommended set, all in shadow, and accepts it as it is") {
      for
        central <- ZIO.service[CentralApi]
        recommended <- central.get("/configuration/edges/rate-limits/recommended")
        policies <- recommended.items("policies")
        stored <- patchSection(Json.Obj("policies" -> Json.Arr(policies*)))
        _ <- setPolicies()
      yield assertTrue(
        recommended.status == Status.Ok,
        policies.map(_.str("name")) == Chunk(
          Some("per-client"),
          Some("per-user"),
          Some("per-user-endpoint"),
          Some("per-address"),
          Some("per-certificate"),
        ),
        policies.forall(_.str("mode").contains("shadow")),
        stored.status == Status.Ok,
      )
    },
    test("a patch that names a revision is refused with 409 when the section has moved on, and a dry run writes nothing") {
      for
        central <- ZIO.service[CentralApi]
        first <- patchSection(Json.Obj("policies" -> Json.Arr(policy("e2e-revision", "shadow", 10))))
        revision <- first.obj.map(_.obj("revisions").flatMap(_.num("rateLimits")).map(_.intValue).getOrElse(-1))
        stale <- central.patch(
          "/configuration/edges/policies",
          Json.Obj("rateLimits" -> Json.Null, "expectedRevisions" -> Json.Obj("rateLimits" -> Json.Num(revision - 1))),
          "edgeId" -> edgeId,
        )
        dry <- patchSection(Json.Null, "dryRun" -> "true")
        stored <- central.get("/configuration/edges/policies", "edgeId" -> edgeId)
        unknown <- central.patch("/configuration/edges/policies", Json.Obj("rateLimit" -> Json.Null), "edgeId" -> edgeId)
        _ <- setPolicies()
      yield assertTrue(
        first.status == Status.Ok,
        stale.status == Status.Conflict,
        stale.body.contains("e2e-revision"),
        dry.status == Status.Ok,
        stored.body.contains("e2e-revision"),
        unknown.status == Status.BadRequest,
        unknown.body.contains("unknown section"),
      )
    },
    test("an enforced policy answers 429 with Retry-After, and the upstream never sees the refused call") {
      withPolicies(policy("e2e-enforced", "enforce", 2)):
        for
          edgeApi <- ZIO.service[EdgeApi]
          f <- ZIO.service[EdgeFixture]
          stub <- ZIO.service[UpstreamStub]
          established <- ZIO.service[EdgeSession]
          _ <- stub.reset
          call = edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
          first <- call
          second <- call
          third <- call
          seen <- stub.requests
        yield assertTrue(
          first.status == Status.Ok,
          second.status == Status.Ok,
          third.status == Status.TooManyRequests,
          third.response.rawHeader("Retry-After").flatMap(_.toIntOption).exists(_ >= 1),
          third.body.contains("e2e-enforced"),
          seen.size == 2,
        ).label("two calls pass the limit of two, the third is refused before the upstream")
    },
    test("a policy in shadow refuses nothing") {
      withPolicies(policy("e2e-shadow", "shadow", 1)):
        for
          edgeApi <- ZIO.service[EdgeApi]
          f <- ZIO.service[EdgeFixture]
          established <- ZIO.service[EdgeSession]
          calls <- ZIO.foreach(1 to 3)(_ => edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth))
        yield assertTrue(calls.forall(_.status == Status.Ok))
    },
    test("removing the policies lifts the limit on the running edge") {
      for
        edgeApi <- ZIO.service[EdgeApi]
        f <- ZIO.service[EdgeFixture]
        established <- ZIO.service[EdgeSession]
        _ <- setPolicies(policy("e2e-lifted", "enforce", 1))
        _ <- reachEdge
        _ <- edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
        refused <- edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
        _ <- setPolicies()
        _ <- reachEdge
        served <- ZIO.foreach(1 to 3)(_ => edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth))
      yield assertTrue(refused.status == Status.TooManyRequests, served.forall(_.status == Status.Ok))
    },
  )

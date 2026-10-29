package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.ast.Json
import zio.test.*

/** Rate limits declared by the owner of an API (levels 2 and 3 of issue #744), end to end: a `rateLimit` on an endpoint, and a limit
  * the resource sets for a client of its audience, set through central's resource API, reach the running edge by its configuration
  * sync, and an `enforce` limit answers `429` with `Retry-After` before the call reaches the upstream, under the fixed policy names
  * `resource-endpoint` and `resource-client`.
  *
  * The unit specs prove the limiter and each sync step on stubs. What only this level shows is the wiring: central's validation,
  * the JSON column and the new `resources.audience_rate_limits` column, the sync of both to the edge, and the limit the caller gets.
  *
  * Nothing here is shared: the fixture registers its own client and resource, so no other spec is limited, and the edge's own
  * policy set is not touched.
  */
object EdgeDeclaredRateLimitSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & EdgeSession]:

  private val UpstreamPort = 9114

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-declared-rate-limit",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      EdgeFixture.Endpoint(name = "limited", method = "GET", path = "/limited"),
      EdgeFixture.Endpoint(name = "free", method = "GET", path = "/free"),
    ),
    awaitProxyReady = true,
  )

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

  private def limit(mode: String, perHour: Int, by: String = "none") =
    Json.Obj(
      "limits" -> Json.Arr(Json.Obj("limit" -> Json.Num(perHour), "windowSeconds" -> Json.Num(3600))),
      "burst" -> Json.Num(perHour),
      "by" -> Json.Str(by),
      "mode" -> Json.Str(mode),
    )

  /** Reloads the edge's configuration, then waits out the limiter's own once-a-second poll of what was loaded. */
  private val reachEdge = ZIO.serviceWithZIO[EdgeApi](_.syncConfiguration) *> ZIO.sleep(3.seconds)

  private def setEndpointLimit(f: EdgeFixture, name: String, path: String, rateLimit: Option[Json]) =
    ZIO.serviceWithZIO[CentralApi](
      _.put(
        "/configuration/resources",
        Fixtures.resourceUpdate(f.resourceId, createEndpoints = List(Fixtures.endpoint(id = f.endpoint(name), path = path, rateLimit = rateLimit))),
      ).flatMap(r => ZIO.fail(RuntimeException(s"PUT resources: ${r.status} ${r.body}")).unless(r.status == Status.NoContent)),
    )

  /** The limits the resource sets for its audience clients, as a whole; an empty list removes them. */
  private def setAudienceLimits(f: EdgeFixture, limits: List[Json]) =
    ZIO.serviceWithZIO[CentralApi](
      _.put("/configuration/resources", Fixtures.resourceUpdate(f.resourceId, audienceRateLimits = Some(limits))),
    )

  private def audienceLimit(f: EdgeFixture, rateLimit: Json, clientId: Option[String] = None) =
    Json.Obj("clientId" -> Json.Str(clientId.getOrElse(f.clientId)), "rateLimit" -> rateLimit)

  def spec = suite("Edge: rate limits declared on an endpoint and on an audience client")(
    test("central refuses a declaration that breaks a policy rule, on an endpoint and on an audience client, and says which") {
      for
        f <- ZIO.service[EdgeFixture]
        central <- ZIO.service[CentralApi]
        endpoint <- central.put(
          "/configuration/resources",
          Fixtures.resourceUpdate(
            f.resourceId,
            createEndpoints =
              List(Fixtures.endpoint(id = f.endpoint("limited"), path = "/limited", rateLimit = Some(limit("enforce", 5).add("burst", Json.Num(6))))),
          ),
        )
        byClient <- setAudienceLimits(f, List(audienceLimit(f, limit("enforce", 5, by = "client"))))
        stranger <- setAudienceLimits(f, List(audienceLimit(f, limit("enforce", 5), clientId = Some("not-in-the-audience"))))
      yield assertTrue(
        endpoint.status == Status.BadRequest,
        endpoint.body.isEmpty,
        byClient.status == Status.BadRequest,
        byClient.body.isEmpty,
        stranger.status == Status.BadRequest,
        stranger.body.isEmpty,
      )
    },
    test("a limit declared on an endpoint refuses that endpoint with 429 under resource-endpoint, and the other endpoint is served") {
      for
        f <- ZIO.service[EdgeFixture]
        edgeApi <- ZIO.service[EdgeApi]
        stub <- ZIO.service[UpstreamStub]
        established <- ZIO.service[EdgeSession]
        _ <- setEndpointLimit(f, "limited", "/limited", Some(limit("enforce", 2, by = "subject")))
        _ <- reachEdge
        _ <- stub.reset
        call = (path: String) => edgeApi.proxy(Method.GET, f.resourceId, path, established.auth)
        first <- call("/limited")
        second <- call("/limited")
        third <- call("/limited")
        free <- ZIO.foreach(1 to 3)(_ => call("/free"))
        seen <- stub.requests
        _ <- setEndpointLimit(f, "limited", "/limited", None)
        _ <- reachEdge
        lifted <- ZIO.foreach(1 to 3)(_ => call("/limited"))
      yield assertTrue(
        first.status == Status.Ok,
        second.status == Status.Ok,
        third.status == Status.TooManyRequests,
        third.response.rawHeader("Retry-After").flatMap(_.toIntOption).exists(_ >= 1),
        third.response.rawHeader("RateLimit-Policy").exists(_.contains("resource-endpoint")),
        // an id would be a metric label of unbounded kind: the name is fixed, nothing identifies the endpoint
        !third.body.contains(f.endpoint("limited")),
        free.forall(_.status == Status.Ok),
        // the refused call never reached the upstream: two limited calls and three free ones
        seen.size == 5,
        lifted.forall(_.status == Status.Ok),
      )
    },
    test("a limit the resource sets for its client refuses that client's calls with 429 under resource-client, and removing it lifts it") {
      for
        f <- ZIO.service[EdgeFixture]
        edgeApi <- ZIO.service[EdgeApi]
        established <- ZIO.service[EdgeSession]
        stored <- setAudienceLimits(f, List(audienceLimit(f, limit("enforce", 2))))
        _ <- reachEdge
        call = edgeApi.proxy(Method.GET, f.resourceId, "/free", established.auth)
        first <- call
        second <- call
        third <- call
        read <- ZIO.serviceWithZIO[CentralApi](_.get("/configuration/resources", "tenantId" -> Fixtures.suiteTenant))
        removed <- setAudienceLimits(f, Nil)
        _ <- reachEdge
        lifted <- ZIO.foreach(1 to 3)(_ => call)
      yield assertTrue(
        stored.status == Status.NoContent,
        first.status == Status.Ok,
        second.status == Status.Ok,
        third.status == Status.TooManyRequests,
        third.response.rawHeader("RateLimit-Policy").exists(_.contains("resource-client")),
        read.body.contains("audienceRateLimits"),
        removed.status == Status.NoContent,
        lifted.forall(_.status == Status.Ok),
      )
    },
    test("a limit in shadow refuses nothing") {
      for
        f <- ZIO.service[EdgeFixture]
        edgeApi <- ZIO.service[EdgeApi]
        established <- ZIO.service[EdgeSession]
        _ <- setAudienceLimits(f, List(audienceLimit(f, limit("shadow", 1))))
        _ <- reachEdge
        calls <- ZIO.foreach(1 to 3)(_ => edgeApi.proxy(Method.GET, f.resourceId, "/free", established.auth))
        _ <- setAudienceLimits(f, Nil)
        _ <- reachEdge
      yield assertTrue(calls.forall(_.status == Status.Ok))
    },
  )

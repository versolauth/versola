package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.ast.Json
import zio.test.*

/** The edge's resource proxy, end to end: a real signed-in browser, a real access token, and a
  * real upstream behind the edge.
  *
  * Edge's unit specs already cover the decision table in isolation. What only this level can
  * show is what the upstream is actually handed once a decision comes out positive — the
  * forwarded path, the surviving headers, and above all whether the browser's session cookie
  * stays on edge's side of the hop.
  */
object EdgeProxySpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub]:

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-proxy",
    resourceUri = UpstreamStub.uri,
    endpoints = List(
      EdgeFixture.Endpoint(name = "list", method = "GET", path = "/items"),
      EdgeFixture.Endpoint(name = "create", method = "POST", path = "/items"),
      EdgeFixture.Endpoint(name = "one", method = "GET", path = "/items/{id}"),
      EdgeFixture.Endpoint(name = "replace", method = "PUT", path = "/items/{id}"),
      EdgeFixture.Endpoint(name = "amend", method = "PATCH", path = "/items/{id}"),
      EdgeFixture.Endpoint(name = "remove", method = "DELETE", path = "/items/{id}"),
      EdgeFixture.Endpoint(name = "secret", method = "GET", path = "/secret", permitted = false),
    ),
    awaitProxyReady = true,
  )

  override val bootstrap
      : ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub] =
    val clients = (E2EConfig.live ++ Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    (clients ++ UpstreamStub.live) >+> EdgeFixture.layer(config)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val edge = ZIO.service[EdgeApi]
  private val fixture = ZIO.service[EdgeFixture]
  private val upstream = ZIO.service[UpstreamStub]

  /** One signed-in browser session, reused by every test that does not need a fresh one:
    * a full authorization code flow per test would dominate the runtime of the suite.
    */
  private val session: ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, EdgeSession] =
    for
      edgeApi <- edge
      authApi <- ZIO.service[OAuthClient]
      f <- fixture
      established <- edgeApi.browserLogin(authApi, f.presetId, f.login, f.password)
    yield established

  private def clean[R, A](effect: ZIO[R, Throwable, A]): ZIO[R & UpstreamStub, Throwable, A] =
    upstream.flatMap(_.reset) *> effect

  def spec = suite("Edge: resource proxy")(
    test("a call with no credential at all is refused") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.None)
        seen <- stub.requests
      yield assertTrue(response.status == Status.Unauthorized) &&
        assertTrue(seen.isEmpty)
          .label("the upstream must never see a request the edge did not authorize")
    },
    test("a call carrying a bearer token that is not a JWT is refused") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.Bearer("not-a-token"))
        seen <- stub.requests
      yield assertTrue(response.status == Status.Unauthorized) && assertTrue(seen.isEmpty)
    },
    test("a call carrying a session cookie that is not a JWT is refused") {
      for
        edgeApi <- edge
        f <- fixture
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.Session(s"${f.presetId}:garbage"))
      yield assertTrue(response.status == Status.Unauthorized)
    },
    test("a call whose token was signed by nobody the edge trusts is refused") {
      for
        edgeApi <- edge
        f <- fixture
        established <- session
        tampered = established.accessToken.dropRight(4) + "AAAA"
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.Bearer(tampered))
      yield assertTrue(response.status == Status.Unauthorized)
        .label("a token whose signature does not verify is worth no more than no token at all")
    },
    test("the browser's session cookie authorizes a call") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
        seen <- stub.lastRequest
      yield assertTrue(response.status == Status.Ok) && assertTrue(seen.nonEmpty)
    },
    test("the same token works as a bearer credential") {
      for
        edgeApi <- edge
        f <- fixture
        established <- session
        response <- clean(edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.Bearer(established.accessToken)))
      yield assertTrue(response.status == Status.Ok)
        .label("a first-party SPA holds the cookie, a native app holds the token; both reach the same resource")
    },
    test("a resource the edge does not know is not found") {
      for
        edgeApi <- edge
        established <- session
        response <- edgeApi.proxy(Method.GET, "e2e-no-such-resource", "/items", established.auth)
      yield assertTrue(response.status == Status.NotFound)
    },
    test("a path the resource does not register is not found") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/unregistered", established.auth)
        seen <- stub.requests
      yield assertTrue(response.status == Status.NotFound) &&
        assertTrue(seen.isEmpty)
          .label("the proxy exposes the endpoints it was told about, not the upstream's whole surface")
    },
    test("a method the endpoint does not register is not found") {
      for
        edgeApi <- edge
        f <- fixture
        established <- session
        response <- clean(edgeApi.proxy(Method.DELETE, f.resourceId, "/items", established.auth))
      yield assertTrue(response.status == Status.NotFound)
        .label("method and path together identify an endpoint, so DELETE /items is a different one from GET /items")
    },
    test("an endpoint the user's role does not reach is forbidden") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/secret", established.auth)
        seen <- stub.requests
      yield assertTrue(response.status == Status.Forbidden) &&
        assertTrue(seen.isEmpty)
          .label("deny by default: the endpoint is registered and routable, and only the permission is missing")
    },
    test("the upstream is called on the path the browser asked for") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        _ <- edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
        seen <- stub.lastRequest
      yield assertTrue(seen.map(_.path).contains("/items"))
    },
    test("the upstream is called with the method the browser used") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        _ <- edgeApi.proxy(Method.POST, f.resourceId, "/items", established.auth, body = Some(Json.Obj()))
        seen <- stub.lastRequest
      yield assertTrue(seen.map(_.method).contains("POST"))
    },
    test("a templated path forwards the concrete segment") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items/42", established.auth)
        seen <- stub.lastRequest
      yield assertTrue(response.status == Status.Ok) &&
        assertTrue(seen.map(_.path).contains("/items/42"))
          .label("the endpoint is registered as /items/{id}; upstream needs the id, not the template")
    },
    test("query parameters reach the upstream") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        _ <- edgeApi.proxy(
          Method.GET,
          f.resourceId,
          "/items",
          established.auth,
          query = List("page" -> "2", "size" -> "50"),
        )
        seen <- stub.lastRequest
      yield assertTrue(seen.flatMap(_.queryParam("page")).contains("2")) &&
        assertTrue(seen.flatMap(_.queryParam("size")).contains("50"))
    },
    test("a JSON request body reaches the upstream unchanged") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        _ <- edgeApi.proxy(
          Method.POST,
          f.resourceId,
          "/items",
          established.auth,
          body = Some(Json.Obj("name" -> Json.Str("Widget"), "count" -> Json.Num(3))),
        )
        seen <- stub.lastRequest
      yield assertTrue(seen.map(_.body).exists(_.contains("Widget"))) &&
        assertTrue(seen.map(_.body).exists(_.contains("3")))
    },
    test("the session cookie does not leak to the upstream") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        _ <- edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
        seen <- stub.lastRequest
      yield assertTrue(!seen.flatMap(_.header("cookie")).exists(_.contains(EdgeApi.sessionCookieName)))
        .label("EDGE_SESSION is edge's own credential; an upstream that receives it could impersonate the browser")
    },
    test("a public resource is called with the caller's own access token") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        _ <- edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
        seen <- stub.lastRequest
      yield assertTrue(seen.flatMap(_.header("authorization")).exists(_.startsWith("Bearer "))) &&
        assertTrue(seen.flatMap(_.header("authorization")).exists(_.endsWith(established.accessToken.takeRight(16))))
          .label("this resource registers no secret, so the upstream is meant to see the user's token")
    },
    test("a header the browser sent is forwarded") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        _ <- edgeApi.proxy(
          Method.GET,
          f.resourceId,
          "/items",
          established.auth,
          headers = List("X-Request-Trace" -> "e2e-proxy"),
        )
        seen <- stub.lastRequest
      yield assertTrue(seen.flatMap(_.header("x-request-trace")).contains("e2e-proxy"))
    },
    test("the upstream's status is handed back to the caller") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        _ <- stub.replyWith(status = Status.Conflict, body = """{"error":"taken"}""")
        established <- session
        response <- edgeApi.proxy(Method.POST, f.resourceId, "/items", established.auth, body = Some(Json.Obj()))
        _ <- stub.reset
      yield assertTrue(response.status == Status.Conflict) &&
        assertTrue(response.body.contains("taken"))
          .label("the proxy is transparent about the upstream's answer, not just about its own decisions")
    },
    test("an upstream failure is reported as the upstream reported it") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        _ <- stub.replyWith(status = Status.InternalServerError, body = "upstream broke")
        established <- session
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
        _ <- stub.reset
      yield assertTrue(response.status == Status.InternalServerError)
        .label("an upstream 500 must not be dressed up as a 200 with an empty body")
    },
    test("a Set-Cookie the upstream tries to set is stripped") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        _ <- stub.replyWith(headers = List("Set-Cookie" -> "upstream_session=abc; Path=/"))
        established <- session
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", established.auth)
        _ <- stub.reset
      yield assertTrue(!response.response.headers.exists(_.headerName.equalsIgnoreCase("set-cookie")))
        .label("an upstream must not be able to plant a cookie on edge's origin, where the session cookie lives")
    },
    test("every registered method reaches the upstream") {
      for
        edgeApi <- edge
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        established <- session
        put <- edgeApi.proxy(Method.PUT, f.resourceId, "/items/7", established.auth, body = Some(Json.Obj()))
        patch <- edgeApi.proxy(Method.PATCH, f.resourceId, "/items/7", established.auth, body = Some(Json.Obj()))
        delete <- edgeApi.proxy(Method.DELETE, f.resourceId, "/items/7", established.auth)
        seen <- stub.requests
      yield assertTrue(put.status == Status.Ok && patch.status == Status.Ok && delete.status == Status.Ok) &&
        assertTrue(seen.map(_.method).toSet == Set("PUT", "PATCH", "DELETE"))
    },
    test("the permissions endpoint reports what the user may do on a resource") {
      for
        edgeApi <- edge
        f <- fixture
        established <- session
        permissions <- edgeApi.permissions(established.auth, resources = List(f.resourceId))
        mine <- permissions.obj.map(_.obj("resources").flatMap(_.obj(f.resourceId)))
      yield assertTrue(permissions.status == Status.Ok) &&
        assertTrue(mine.map(_.strings("permissions")).contains(Set(f.permission)))
    },
    test("the permissions endpoint reports an empty set for a resource the user cannot touch") {
      for
        edgeApi <- edge
        established <- session
        permissions <- edgeApi.permissions(established.auth, resources = List("auth"))
        other <- permissions.obj.map(_.obj("resources").flatMap(_.obj("auth")))
      yield assertTrue(other.map(_.strings("permissions")).contains(Set.empty[String]))
        .label("the console hides what it cannot do; a permission listed here but denied at the proxy is a broken UI")
    },
    test("the permissions endpoint answers about the resources it was asked about and no others") {
      for
        edgeApi <- edge
        f <- fixture
        established <- session
        permissions <- edgeApi.permissions(established.auth, resources = List(f.resourceId))
        named <- permissions.obj.map(_.obj("resources").map(_.fields.map(_._1).toSet))
      yield assertTrue(named.contains(Set(f.resourceId)))
    },
    test("the permissions endpoint answers about nothing when asked about nothing") {
      for
        edgeApi <- edge
        established <- session
        permissions <- edgeApi.permissions(established.auth)
        named <- permissions.obj.map(_.obj("resources").map(_.fields.size))
      yield assertTrue(permissions.status == Status.Ok) && assertTrue(named.contains(0))
        .label("the caller names the resources it renders; an unasked-for answer would be a permission listing")
    },
    test("the permissions endpoint tells the console whether it is talking to production") {
      for
        edgeApi <- edge
        established <- session
        permissions <- edgeApi.permissions(established.auth)
        isProd <- permissions.obj.map(_.bool("isProd"))
      yield assertTrue(isProd.contains(false))
        .label("non-prod affordances such as revealing a generated password hang off this flag")
    },
    test("the permissions endpoint refuses an anonymous caller") {
      for
        edgeApi <- edge
        permissions <- edgeApi.permissions(EdgeAuth.None)
      yield assertTrue(permissions.status == Status.Unauthorized)
    },
    test("the permissions endpoint refuses a token it cannot verify") {
      for
        edgeApi <- edge
        permissions <- edgeApi.permissions(EdgeAuth.Bearer("not-a-token"))
      yield assertTrue(permissions.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)

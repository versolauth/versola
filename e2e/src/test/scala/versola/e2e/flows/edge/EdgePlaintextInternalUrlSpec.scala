package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** #567: an edge-fronted web client whose certificate central issued, on an edge whose `versola-internal-url` is plaintext.
  *
  * The staged edge reaches auth through the TLS terminator (`https://localhost:9443`), so for a client that authenticates by
  * certificate it presents the certificate to the terminator, which forwards it in a header (`EdgeIssuedCertificateSpec`). On an
  * edge with a plaintext internal url there is no handshake to carry a certificate, and the calls that present one -- `/par`,
  * `/token` and the certificate-bound `/userinfo` -- go to auth's own mutual-TLS listener (`native.auth-mutual-tls-url`, #552).
  * That route is covered by unit tests (`SSOClientSpec`) and by a real handshake in the edge module
  * (`SSOClientMutualTlsHandshakeSpec`); what only this level shows is that auth's listener serves the whole conversation of a
  * web login to a client it knows only by the subject central registered it under.
  *
  * The edge is a second one of the staged build, on the staged edge's id and database, started by the spec with
  * `versola-internal-url` pointed at auth's plain port and no pin for a terminator. It is not the staged stack's edge: CI and
  * `scripts/start-local.sh` run only that one. Nothing in the registration says which edge serves the client, so the fixture
  * is the one `EdgeIssuedCertificateSpec` uses, with pushed authorization requests required (a web client of this kind is
  * registered with them) and an [[EdgeApi]] that talks to the second edge.
  *
  * A request that took the other route would be refused: auth's plain port knows nothing of a certificate that was not in a
  * header, so a sign-in that completes is the listener's doing.
  */
object EdgePlaintextInternalUrlSpec
  extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub]:

  private val UpstreamPort = 9116

  private val EdgeUrl = "http://localhost:9141"
  // MPORT too: the staged edge holds the default one (8083) for its own mutual-TLS listener.
  private val EdgePorts = Map("PORT" -> "9141", "DPORT" -> "9142", "MPORT" -> "9143")

  /** What makes the edge one without the terminator. `edge-url` is what the login's redirect URI and a DPoP proof's `htu` are
    * made from. The pin the staged edge holds for the terminator is dropped with the terminator; `native { }` stays as the
    * staged edge has it, which is where the certificate goes. */
  private def withoutTerminator(authUrl: String): String =
    s"""versola-internal-url = "$authUrl"
       |versola-internal-trusted-certificates = []
       |edge-url = "$EdgeUrl"
       |""".stripMargin

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-plaintext-internal-url",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      EdgeFixture.Endpoint(
        name = "items",
        method = "GET",
        path = "/items",
        permitted = true,
        fetchUserInfo = false,
        allow = None,
        inject = Nil,
        stepUpCondition = None,
        stepUpAcr = None,
        maxAge = None,
      ),
      // Auth answers `/userinfo` for a certificate-bound token only over a connection presenting that certificate, so this
      // endpoint has edge present it a second time, on the listener.
      EdgeFixture.Endpoint(
        name = "profile",
        method = "GET",
        path = "/profile",
        permitted = true,
        fetchUserInfo = true,
        allow = None,
        inject = List(Fixtures.inject("header", "X-User-Sub", "user.sub")),
        stepUpCondition = None,
        stepUpAcr = None,
        maxAge = None,
      ),
    ),
    scopes = Set("openid", "email"),
    presetScope = Set("openid"),
    postLogoutRedirectUri = None,
    cookiePath = None,
    awaitProxyReady = true,
    privateKeyJwt = false,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = true,
    mutualTls = false,
    nativeApp = false,
    issuedCertificate = true,
    enrolledCertificate = false,
    groupCertificate = None,
  )

  /** The second edge, started and ready, as an [[EdgeApi]]; stopped when the suite's environment is released. */
  private val secondEdge: ZLayer[Client & E2EConfig, Throwable, EdgeApi] =
    ZLayer.scoped:
      for
        client <- ZIO.service[Client]
        e2e <- ZIO.service[E2EConfig]
        running <- ExtraService.start(e2e.edgeLauncher, e2e.edgeEnvConf, withoutTerminator(e2e.authUrl), EdgePorts)
        _ <- ExtraService.awaitReady(running)
      yield EdgeApi(client, e2e.copy(edgeUrl = EdgeUrl))

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub] =
    val base = E2EConfig.live ++ Client.default
    val services = base >>> (OAuthClient.live ++ CentralApi.live)
    (services ++ (base >>> secondEdge) ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val edge = ZIO.service[EdgeApi]
  private val auth = ZIO.service[OAuthClient]
  private val fixture = ZIO.service[EdgeFixture]
  private val upstream = ZIO.service[UpstreamStub]

  private val signIn: ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, EdgeSession] =
    for
      edgeApi <- edge
      authApi <- auth
      f <- fixture
      session <- edgeApi.browserLogin(authApi, f.presetId, f.login, f.password)
    yield session

  /** `cnf` of the access token an `EDGE_SESSION` cookie carries. */
  private def confirmation(cookie: String): Task[Json.Obj] =
    for
      token <- ZIO.succeed(cookie.dropWhile(_ != ':').drop(1))
      payload <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(token.split('.')(1)), "UTF-8"))
      json <- ZIO.fromEither(payload.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield json.get("cnf").flatMap(_.asObject).getOrElse(Json.Obj())

  def spec = suite("a certificate client on an edge whose internal url is plaintext")(
    test("signs in: the pushed request and the code exchange reach auth's mutual-TLS listener with the issued certificate") {
      for session <- signIn
      yield assertTrue(session.cookie.nonEmpty)
    },
    test("the session's token is bound to the certificate, which is what the listener issued it for") {
      for
        session <- signIn
        cnf <- confirmation(session.cookie)
      yield assertTrue(cnf.get("x5t#S256").flatMap(_.asString).exists(_.nonEmpty))
    },
    test("proxies a request made with that session") {
      for
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        edgeApi <- edge
        session <- signIn
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", session.auth)
        seen <- stub.requests.map(_.findLast(_.path == "/items"))
      yield assertTrue(response.status == Status.Ok, seen.isDefined)
    },
    test("reaches an endpoint whose authorization needs userinfo, which auth answers only over the certificate") {
      for
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        edgeApi <- edge
        session <- signIn
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/profile", session.auth)
        seen <- stub.requests.map(_.findLast(_.path == "/profile"))
      yield assertTrue(
        response.status == Status.Ok,
        seen.flatMap(_.header("X-User-Sub")).exists(_.nonEmpty),
      )
    },
  ) @@ TestAspect.sequential

package versola.e2e.flows.edge

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Client, Method, Status}
import zio.test.*

/** #440: an edge-fronted web client whose certificate central issues from its own CA, rather
  * than the registration supplying one.
  *
  * The registration names neither the certificate nor the subject it is recognised by. What only
  * this level shows is that the certificate central issues is one the whole path accepts: central
  * encrypts it to edge, edge presents it through the TLS terminator in front of auth (which only
  * asks for a certificate from the CA it advertises), and auth recognises the client by the
  * subject central registered it under -- and that the certificate itself is never handed back.
  *
  * Needs central's `client-certificate-authority`, which `scripts/gen-env.scala`'s `local`
  * target points at the terminator's CA (see develop.md).
  */
object EdgeIssuedCertificateSpec
  extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub]:

  /** Its own upstream origin -- see EdgePrivateKeyJwtSpec's comment on why sharing one 500s
    * instead of merely conflicting. */
  private val UpstreamPort = 9110

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-issued-certificate",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      EdgeFixture.Endpoint(name = "items", method = "GET", path = "/items"),
      // Auth answers `/userinfo` for a certificate-bound token only over a connection
      // presenting that certificate, so this endpoint has edge present it a second time.
      EdgeFixture.Endpoint(
        name = "profile",
        method = "GET",
        path = "/profile",
        fetchUserInfo = true,
        inject = List(Fixtures.inject("header", "X-User-Sub", "user.sub")),
      ),
    ),
    issuedCertificate = true,
    awaitProxyReady = true,
  )

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub] =
    val clients = (E2EConfig.live ++ Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val edge = ZIO.service[EdgeApi]
  private val auth = ZIO.service[OAuthClient]
  private val central = ZIO.service[CentralApi]
  private val fixture = ZIO.service[EdgeFixture]
  private val upstream = ZIO.service[UpstreamStub]

  private val signIn: ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, EdgeSession] =
    for
      edgeApi <- edge
      authApi <- auth
      f <- fixture
      session <- edgeApi.browserLogin(authApi, f.presetId, f.login, f.password)
    yield session

  def spec = suite("edge fronting a client whose certificate central issued")(
    test("the client is registered tls_client_auth by the issued certificate's subject, which stays with edge") {
      for
        centralApi <- central
        f <- fixture
        listed <- centralApi.get("/configuration/clients", "tenantId" -> Fixtures.suiteTenant)
          .flatMap(_.items("clients"))
        client = listed.find(_.str("id").contains(f.clientId))
      yield assertTrue(
        client.flatMap(_.str("authMethod")).contains("tls_client_auth"),
        client.flatMap(_.obj("mtlsAuth")).flatMap(_.str("subjectType")).contains("subject_dn"),
        client.flatMap(_.obj("mtlsAuth")).flatMap(_.str("subjectValue"))
          .contains(s"CN=${f.clientId},OU=${Fixtures.suiteTenant},O=Versola"),
        f.certificate.isEmpty,
      ) && assertTrue(client.exists(_.get("edgeClientCertificate").isEmpty))
        .label("the operator's listing never carries the certificate or its key")
    },
    test("signs in, with edge presenting the issued certificate through the terminator") {
      for session <- signIn
      yield assertTrue(session.cookie.nonEmpty)
    },
    test("proxies a request made with the certificate-bound token it was issued") {
      for
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        edgeApi <- edge
        session <- signIn
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", session.auth)
        seen <- stub.lastRequest
      yield assertTrue(response.status == Status.Ok, seen.isDefined)
    },
    test("reaches an endpoint whose authorization needs userinfo") {
      for
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        edgeApi <- edge
        session <- signIn
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/profile", session.auth)
        seen <- stub.lastRequest
      yield assertTrue(
        response.status == Status.Ok,
        seen.flatMap(_.header("X-User-Sub")).exists(_.nonEmpty),
      )
    },
  )

package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.test.*

/** An edge fronting a client that authenticates by certificate rather than by secret -- the
  * credential `SSOClient.ClientCredential.MutualTls` carries, and the one the security fix on
  * `SSOClient.scala` is about: presenting it is a TLS handshake, not a request, and that
  * handshake's far side has to be authenticated or the certificate (and the session it opens)
  * would be handed to whatever answered the address.
  *
  * `EdgeFixture.Config.mutualTls` points `versola-internal-url` nowhere special -- it is the
  * same address every other edge spec's `/token` call already goes through (see
  * `scripts/gen-env.scala`'s nginx). What is special about this one is only the credential:
  * central hands the edge a certificate instead of a secret or a signing key, and reaching a
  * session at all here means edge's SSOClient completed a real TLS handshake against that
  * nginx, presented the certificate, validated nginx's own server certificate against
  * `versola-internal-trusted-certificates`, and had the result -- forwarded as a header, RFC
  * 8705 §6.5 -- accepted by auth for the client central registered it for.
  *
  * The unit suites (`SSOClientMutualTlsHandshakeSpec`) already prove the handshake itself
  * against a TLS server standing in for whatever terminates it. What only this level shows is
  * that the pieces line up across the real services: that central's `edgeClientCertificate`
  * survives the trip through the sync encryption `OAuthClientsSyncClient` decrypts, that the
  * certificate it hands edge is the one nginx actually presents, and that auth's own
  * `self_signed_tls_client_auth` matching accepts the header nginx forwards.
  *
  * And what only a proxied request shows, beyond the session: the token auth issues this
  * client is bound to the certificate (RFC 8705 §3, `cnf.x5t#S256`), so every later hop has
  * to keep holding that binding up -- edge reading the claim at all, and edge presenting the
  * same certificate again on `/userinfo`, which auth refuses to a connection without it.
  */
object EdgeMutualTlsSpec
  extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub]:

  /** Its own upstream origin -- see EdgePrivateKeyJwtSpec's comment on why sharing one 500s
    * instead of merely conflicting. */
  private val UpstreamPort = 9107

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-mutual-tls",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      EdgeFixture.Endpoint(name = "items", method = "GET", path = "/items"),
      // The endpoint that makes edge call `/userinfo` with the token it was issued, and the
      // injected header that proves the call came back with claims rather than a refusal.
      EdgeFixture.Endpoint(
        name = "profile",
        method = "GET",
        path = "/profile",
        fetchUserInfo = true,
        inject = List(Fixtures.inject("header", "X-User-Sub", "user.sub")),
      ),
    ),
    mutualTls = true,
    awaitProxyReady = true,
  )

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub] =
    val clients = (E2EConfig.live ++ Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)

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

  def spec = suite("edge fronting a self-signed mutual-TLS client")(
    test("signs in with no secret anywhere, over a real TLS handshake through nginx") {
      for
        f <- fixture
        session <- signIn
      yield assertTrue(
        // Reaching a session at all means /token accepted the certificate central handed
        // this edge -- the whole chain from registration to the handshake held.
        session.cookie.nonEmpty,
        // The fixture generated a certificate rather than a secret being usable: confirms
        // this test is actually exercising the credential it claims to.
        f.certificate.isDefined,
      )
    },
    // The session this client opens is worth nothing if edge cannot spend it. Nothing about
    // this endpoint is mutual-TLS-specific -- it is the plainest proxied call there is --
    // which is the point: the only thing that can fail it here is the binding the token
    // carries because of how the session was opened.
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
    // RFC 8705 §3 again, one hop further out: auth refuses this token on `/userinfo` unless
    // the connection asking presents the certificate it is bound to. Only the injected header
    // proves the call came back with claims -- a refusal is a 401 the upstream never sees.
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

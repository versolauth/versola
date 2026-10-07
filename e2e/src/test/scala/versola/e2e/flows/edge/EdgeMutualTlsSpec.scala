package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.*
import zio.json.ast.Json
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

  /** An authorization code flow for the fixture's user, redeemed over the client's own
    * certificate *and* under a DPoP key -- the one token `auth` binds to both (RFC 8705 §3 and
    * RFC 9449 §5 at once). Redeemed here rather than by edge, so nothing is proxied by accident
    * and the key stays in this spec's hands.
    */
  private def doublyBoundToken(
      prover: DpopProver,
  ): ZIO[OAuthClient & EdgeApi & EdgeFixture, Throwable, String] =
    for
      authApi <- auth
      edgeApi <- edge
      f <- fixture
      certificate <- ZIO.fromOption(f.certificate).orElseFail(RuntimeException("the fixture holds no certificate"))
      started <- authApi.authorizeRaw(f.clientId, edgeApi.completeUri)
      conversation <- ZIO.fromOption(started.conversationCookie)
        .orElseFail(RuntimeException(s"the OP started no conversation (status=${started.response.status})"))
      challenge <- authApi.getChallenge(conversation)
      submitted <- authApi.submitLoginPassword(conversation, f.login, f.password, challenge.csrf)
      code <- submitted.assertRedirect
      issued <- authApi.token(
        code,
        started.verifier,
        clientId = Some(f.clientId),
        clientSecret = Some(f.clientSecret),
        redirectUri = Some(edgeApi.completeUri),
        dpop = Some(prover),
        certificate = Some(certificate.urlEncodedPem),
      ).success
    yield issued.accessToken

  private def confirmation(accessToken: String): Task[Json.Obj] =
    for
      payload <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(accessToken.split('.')(1)), "UTF-8"))
      json <- ZIO.fromEither(payload.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield json.get("cnf").collect { case obj: Json.Obj => obj }.getOrElse(Json.Obj())

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
    // #452. A token bound to a key and a certificate is protected by the key alone if edge
    // takes a valid proof as the whole answer -- and the certificate half is the one edge
    // cannot be shown, since no caller of edge holds the certificate it is bound to.
    test("refuses a token bound to both a key and a certificate, proof or not") {
      for
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        edgeApi <- edge
        prover <- DpopProver.make
        token <- doublyBoundToken(prover)
        cnf <- confirmation(token)
        htu = edgeApi.proxyUrl(f.resourceId, "/items")
        proof <- prover.proof(Method.GET, htu, accessToken = Some(token))
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.Dpop(token, proof))
        seen <- stub.requests
      yield assertTrue(
        cnf.get("jkt").isDefined,
        cnf.get("x5t#S256").isDefined,
      ).label(s"the control: this token has to carry both bindings, got $cnf") &&
        assertTrue(response.status == Status.Unauthorized, seen.isEmpty)
          .label("a valid proof must not carry a certificate-bound token past edge")
    },
  )

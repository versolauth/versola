package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** An API behind edge's `/resources` that is called with an access token bound to the *caller's*
  * client certificate (RFC 8705 §3, `cnf.x5t#S256`) -- a bank calling an operator's API, say.
  *
  * Edge cannot see a caller's certificate on its plain listener, so it used to refuse every such
  * token outside its own session cookie. Its mutual-TLS listener (`mutual-tls` in `edge.conf`, MPORT,
  * which serves `/resources` and nothing else) reads the certificate off the handshake and honours the
  * token for the holder of the certificate it names. The endpoint that needs `/userinfo` is the test
  * only this level can give: auth enforces the same binding on `/userinfo` and edge holds neither the
  * caller's certificate nor a proof, so it calls auth's mutual-TLS listener with its *own* certificate,
  * which auth accepts because it chains to the issuer of edge certificates it trusts
  * (`mutual-tls.edge-trusted-certificates`) and names `urn:versola:edge:<id>`. Every component spec on
  * that path stubs the one next to it; here the certificates are real and so are both handshakes.
  *
  * The "bank" is a client of its own, registered `tls_client_auth` for a certificate signed by the CA
  * edge's listener trusts for callers, and it obtains its token for the fixture's user over the header
  * path like every other certificate-bound client in this suite.
  */
object EdgeResourcesMutualTlsSpec
  extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & EdgeResourcesMutualTlsSpec.Bank]:

  /** The bank: its client, and the certificate that client authenticates with and that its tokens are bound to. */
  case class Bank(clientId: String, certificate: EdgeCertificate)

  /** Its own upstream origin -- see EdgePrivateKeyJwtSpec's comment on why sharing one 500s instead of merely
    * conflicting. */
  private val UpstreamPort = 9112

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-resources-mtls",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      EdgeFixture.Endpoint(name = "items", method = "GET", path = "/items"),
      // The endpoint that makes edge call `/userinfo`, and the header built from its answer.
      EdgeFixture.Endpoint(
        name = "profile",
        method = "GET",
        path = "/profile",
        fetchUserInfo = true,
        inject = List(Fixtures.inject("header", "X-User-Sub", "user.sub")),
      ),
    ),
    awaitProxyReady = true,
  )

  private val bank: ZLayer[OAuthClient & CentralApi & EdgeApi & EdgeFixture & E2EConfig, Throwable, Bank] =
    ZLayer.fromZIO:
      for
        auth <- ZIO.service[OAuthClient]
        central <- ZIO.service[CentralApi]
        edgeApi <- ZIO.service[EdgeApi]
        f <- ZIO.service[EdgeFixture]
        clientId <- CentralApi.id("bank-client")
        commonName = s"e2e-bank-${clientId.takeRight(12)}"
        // Signed by the CA edge's listener trusts for callers (and auth's own listener does for clients).
        certificate <- EdgeCertificate.forAuthListener(commonName)
        // Central refuses to register an mtlsAuth client until the tenant names the header the proxy puts the
        // certificate in; the vocabulary is the one every other spec sets, since this overwrites every field.
        _ <- auth.upsertChallengeSettings(
          acrVocabulary = Map(
            Acr.OtpLevel -> List("otp"),
            Acr.PasswordLevel -> List("password"),
            Acr.PasskeyLevel -> List("passkey"),
          ),
          mtlsCertificateHeader = Some(OAuthClient.mtlsCertificateHeader),
          mtlsCertificateEncoding = Some("urlEncodedPem"),
        )
        _ <- auth.registerClient(
          clientId,
          "Bank",
          redirectUris = Set(edgeApi.completeUri),
          allowedScopes = Set("openid"),
          authFlow = Some(Flows.loginPasswordAuthFlow),
          authMethod = "tls_client_auth",
          mtlsAuth = Some(Fixtures.mutualTlsAuth("subject_dn", s"CN=$commonName")),
          certificateBoundAccessTokens = true,
        ).success
        // A token's audience names the resources whose audience lists the client.
        updated <- central.put("/configuration/resources", Fixtures.resourceUpdate(f.resourceId, addAudience = Set(clientId)))
        _ <- ZIO.fail(RuntimeException(s"could not put the bank in the resource's audience: ${updated.status} ${updated.body}"))
          .unless(updated.status.isSuccess)
        _ <- auth.syncConfiguration()
        _ <- edgeApi.syncConfiguration
      yield Bank(clientId, certificate)

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Bank] =
    val clients = (E2EConfig.live ++ Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live ++ E2EConfig.live)
    (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config) >+> bank

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val edge = ZIO.service[EdgeApi]
  private val auth = ZIO.service[OAuthClient]
  private val fixture = ZIO.service[EdgeFixture]
  private val upstream = ZIO.service[UpstreamStub]
  private val theBank = ZIO.service[Bank]
  private val e2eConfig = ZIO.service[E2EConfig]

  /** The bank's token for the fixture's user, bound to the bank's certificate. Redeemed here, not by edge, so
    * nothing is proxied by accident and the certificate stays in this spec's hands. */
  private val boundToken: ZIO[OAuthClient & EdgeApi & EdgeFixture & Bank, Throwable, String] =
    for
      authApi <- auth
      edgeApi <- edge
      f <- fixture
      b <- theBank
      started <- authApi.authorizeRaw(b.clientId, edgeApi.completeUri)
      conversation <- ZIO.fromOption(started.conversationCookie)
        .orElseFail(RuntimeException(s"the OP started no conversation (status=${started.response.status})"))
      challenge <- authApi.getChallenge(conversation)
      submitted <- authApi.submitLoginPassword(conversation, f.login, f.password, challenge.csrf)
      code <- submitted.assertRedirect
      issued <- authApi.token(
        code,
        started.verifier,
        clientId = Some(b.clientId),
        // `tls_client_auth`: the certificate is the credential, there is no secret to send.
        clientSecret = Some(""),
        redirectUri = Some(edgeApi.completeUri),
        certificate = Some(b.certificate.urlEncodedPem),
      ).success
    yield issued.accessToken

  private def confirmation(accessToken: String): Task[Json.Obj] =
    for
      payload <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(accessToken.split('.')(1)), "UTF-8"))
      json <- ZIO.fromEither(payload.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield json.get("cnf").collect { case obj: Json.Obj => obj }.getOrElse(Json.Obj())

  /** One call to edge's mutual-TLS listener, presenting `certificate` in the handshake (none at all for `None`). */
  private def overMutualTls(
      path: String,
      token: String,
      certificate: Option[EdgeCertificate],
  ): ZIO[E2EConfig & EdgeFixture, Throwable, Response] =
    for
      cfg <- e2eConfig
      f <- fixture
      trust = ClientSSLConfig.FromCertFile(cfg.edgeMutualTlsTrustedCertificates)
      ssl <- certificate.fold(ZIO.succeed(trust))(_.clientSsl(cfg.edgeMutualTlsTrustedCertificates))
      url <- ZIO.fromEither(URL.decode(s"${cfg.edgeMutualTlsUrl}$path")).mapError(RuntimeException(_))
      request = Request.get(url).addHeader(Header.Authorization.Bearer(token))
      response <- ZIO.scoped:
        for
          client <- Client.default.build.map(_.get[Client])
          response <- Client.batched(request).provide(ZLayer.succeed(client.ssl(ssl)))
          // Read inside the scope: the body is a stream of the connection that closes with it.
          body <- response.body.asString
        yield response.copy(body = Body.fromString(body))
    yield response

  private def resourcePath(f: EdgeFixture, path: String) = s"/resources/${f.resourceId}$path"

  def spec = suite("edge's mutual-TLS listener for /resources")(
    test("the bank's token is bound to the bank's certificate") {
      for
        token <- boundToken
        cnf <- confirmation(token)
      yield assertTrue(cnf.get("x5t#S256").isDefined, cnf.get("jkt").isEmpty)
        .label(s"the control for everything below: a certificate-bound, not key-bound, token -- got $cnf")
    },
    test("serves the token over the certificate it is bound to, fetching userinfo and injecting its claims") {
      for
        f <- fixture
        b <- theBank
        stub <- upstream
        _ <- stub.reset
        token <- boundToken
        response <- overMutualTls(resourcePath(f, "/profile"), token, Some(b.certificate))
        seen <- stub.requests.map(_.findLast(_.path == "/profile"))
      yield assertTrue(response.status == Status.Ok)
        .label("edge accepted the token for the holder of its certificate, and auth honoured edge's own call to /userinfo") &&
        assertTrue(seen.flatMap(_.header("X-User-Sub")).contains(f.userId.toString))
          .label("the injected header is built from the userinfo response, so it proves that call came back")
    },
    test("serves an endpoint that needs no userinfo as well") {
      for
        f <- fixture
        b <- theBank
        stub <- upstream
        _ <- stub.reset
        token <- boundToken
        response <- overMutualTls(resourcePath(f, "/items"), token, Some(b.certificate))
        seen <- stub.requests.map(_.findLast(_.path == "/items"))
      yield assertTrue(response.status == Status.Ok, seen.isDefined)
    },
    test("refuses the same token on the plain listener, where edge cannot see a certificate") {
      for
        f <- fixture
        edgeApi <- edge
        stub <- upstream
        _ <- stub.reset
        token <- boundToken
        response <- edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.Bearer(token))
        seen <- stub.requests
      yield assertTrue(response.status == Status.Unauthorized, seen.isEmpty)
        .label("certificate_bound_token_outside_session: nothing in the request proves the caller holds the certificate")
    },
    test("refuses the token over a certificate it is not bound to, though the listener trusts that certificate") {
      for
        f <- fixture
        stub <- upstream
        _ <- stub.reset
        token <- boundToken
        // Signed by the same CA, so the handshake completes: only the binding can refuse this.
        other <- EdgeCertificate.forAuthListener(s"e2e-other-${java.util.UUID.randomUUID().toString.take(8)}")
        response <- overMutualTls(resourcePath(f, "/items"), token, Some(other))
        seen <- stub.requests
      yield assertTrue(response.status == Status.Unauthorized, seen.isEmpty)
        .label("a stolen certificate-bound token is useless without the certificate it names")
    },
    test("refuses a connection that presents no certificate in the handshake") {
      for
        f <- fixture
        token <- boundToken
        result <- overMutualTls(resourcePath(f, "/items"), token, None).either
      yield assertTrue(result.isLeft)
    },
    test("serves nothing but /resources") {
      for
        f <- fixture
        b <- theBank
        token <- boundToken
        login <- overMutualTls(s"/login/${f.presetId}", token, Some(b.certificate))
        permissions <- overMutualTls("/permissions/me", token, Some(b.certificate))
      yield assertTrue(login.status == Status.NotFound, permissions.status == Status.NotFound)
        .label("the listener a caller's certificate opens is not the browser's login, nor the console's permissions")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)

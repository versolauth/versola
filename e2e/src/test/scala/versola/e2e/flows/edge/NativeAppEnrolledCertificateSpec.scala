package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Response, Status}
import zio.json.*
import zio.test.*

/** #463: a native app fronted by edge whose certificate the edge itself generated the key of and
  * enrolled for, so central stores neither the certificate nor a key.
  *
  * Only a native app can be enrolled for (a web client's tokens are bound to its certificate, which
  * replicas enrolling separately would not share). What only this level shows: the sync tells the edge
  * what the certificate must say, the edge's own request is signed by central's CA through the
  * endpoint only a registered edge may call, and the certificate that comes back is the one auth's
  * mutual-TLS listener accepts -- `/native/start` pushes the request over it, authenticated by that
  * certificate alone.
  *
  * Needs the staged stack with auth's mutual-TLS listener, edge's `native { }` block and central's
  * `client-certificate-authority`, which `scripts/gen-env.scala`'s `local` target writes (develop.md).
  */
object NativeAppEnrolledCertificateSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub]:

  private val UpstreamPort = 9111

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-native-enrolled",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(EdgeFixture.Endpoint(name = "items", method = "GET", path = "/items")),
    nativeApp = true,
    enrolledCertificate = true,
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

  /** `/native/start`'s answer, as the SDK reads it. */
  private case class Started(
      client_id: String,
      request_uri: String,
      expires_in: Long,
      authorization_endpoint: String,
      state: String,
      blob: String,
      token_endpoint: String,
      revocation_endpoint: String,
  ) derives JsonDecoder

  /** RFC 9449 §8/§9: a `DPoP-Nonce` challenge from whoever answered -- auth for the relayed
    * calls, edge for the proxied one -- answered once with the nonce signed into a fresh proof.
    * A second challenge would mean the nonce just issued is not accepted. */
  private def withNonce(call: Option[String] => Task[Response]): Task[Response] =
    call(None).flatMap: first =>
      DpopProver.nonceOf(first) match
        case Some(nonce) if first.status == Status.BadRequest || first.status == Status.Unauthorized =>
          call(Some(nonce))
        case _ => ZIO.succeed(first)

  private def body(response: Response): Task[String] = response.body.asString

  private def start(prover: DpopProver): ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, Started] =
    for
      edgeApi <- edge
      f <- fixture
      authApi <- auth
      attempt = prover.proof(Method.POST, edgeApi.nativeUrl("start", f.clientId))
        .flatMap(proof => edgeApi.native("start", f.clientId, List("scope" -> "openid offline_access"), Some(proof)))
      // A client central registered a moment ago reaches edge's cache (404 here) and auth's
      // (401 invalid_client from /par, relayed) by a notification the registering request does
      // not wait for -- so an early answer of either is a fresh sync and another try, not a result.
      response <- withNonce(nonce =>
        prover.proof(Method.POST, edgeApi.nativeUrl("start", f.clientId), nonce = nonce)
          .flatMap(proof => edgeApi.native("start", f.clientId, List("scope" -> "openid offline_access"), Some(proof))),
      ).flatMap: first =>
        if first.status != Status.NotFound && first.status != Status.Unauthorized then ZIO.succeed(first)
        else
          (edgeApi.syncConfiguration *> authApi.syncConfiguration() *> withNonce(nonce =>
            prover.proof(Method.POST, edgeApi.nativeUrl("start", f.clientId), nonce = nonce)
              .flatMap(proof => edgeApi.native("start", f.clientId, List("scope" -> "openid offline_access"), Some(proof))),
          ))
            .repeat(Schedule.spaced(1.second) *> Schedule.recurUntil[Response](r =>
              r.status != Status.NotFound && r.status != Status.Unauthorized,
            ))
            .timeout(60.seconds)
            .someOrElse(first)
      text <- body(response)
      started <- ZIO.fromEither(text.fromJson[Started])
        .mapError(reason => RuntimeException(s"/native/start answered ${response.status}: $text ($reason)"))
    yield started

  def spec = suite("a native app whose edge enrolled for its certificate")(
    test("is registered by the default subject, and central holds neither certificate nor key") {
      for
        centralApi <- central
        f <- fixture
        listed <- centralApi.get("/configuration/clients", "tenantId" -> Fixtures.suiteTenant)
          .flatMap(_.items("clients"))
        client = listed.find(_.str("id").contains(f.clientId))
      yield assertTrue(
        client.flatMap(_.str("authMethod")).contains("tls_client_auth"),
        client.flatMap(_.str("applicationType")).contains("native"),
        client.flatMap(_.obj("mtlsAuth")).flatMap(_.str("subjectValue"))
          .contains(s"CN=${f.clientId},OU=${Fixtures.suiteTenant},O=Versola"),
        f.certificate.isEmpty,
      ) && assertTrue(client.exists(_.get("edgeClientCertificate").isEmpty))
        .label("the operator's listing never carries a certificate or a key")
    },
    test("starts a login on auth's mutual-TLS listener, authenticated by the certificate edge enrolled for") {
      for
        prover <- DpopProver.make
        started <- start(prover)
      yield assertTrue(started.request_uri.nonEmpty, started.client_id.nonEmpty)
    },
  )

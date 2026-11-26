package versola.central.configuration.edges

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.metadata.ServerMetadataService
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

/** The route an edge reaches before it has a certificate: the token is the credential, and every kind of bad token is
  * the same answer. */
object EdgeEnrollControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val edgeId = EdgeId("edge-1")

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val signingKey = Json.Obj("kty" -> Json.Str("RSA"), "kid" -> Json.Str("edge-key"))

  private val body = EnrollEdgeRequest(edgeId = Some(edgeId), token = "TOKEN", csr = "REQUEST", signingKey = Some(signingKey)).toJson

  private val config = TestCentralConfig.config.copy(
    auth = TestCentralConfig.config.auth.copy(mtlsUrl = Some(URL.decode("https://id-mtls.example.com").toOption.get)),
    publicUrls = Some(CentralConfig.PublicUrlsConfig(
      enroll = URL.decode("https://enroll.example.com").toOption.get,
      mtls = URL.decode("https://central-mtls.example.com").toOption.get,
    )),
  )

  private def environment(identity: EdgeIdentityService, central: CentralConfig = config) =
    val metadata = stub[ServerMetadataService]
    for
      tracing <- tracingLayer.build
      _ <- metadata.getMetadata.succeedsWith(Some(Json.Obj("issuer" -> Json.Str("https://id.example.com"))))
    yield ZEnvironment[EdgeIdentityService](identity) ++ ZEnvironment[CentralConfig](central) ++ ZEnvironment[ServerMetadataService](
      metadata,
    ) ++ tracing

  private def post(
      identity: EdgeIdentityService,
      payload: String = body,
      central: CentralConfig = config,
  ): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      env <- environment(identity, central)
      _ <- TestClient.addRoutes(Observability.handleErrors(EdgeEnrollController.routes.provideEnvironment(env)))
      response <- client.batched(
        Request.post(URL.root / "edge" / "enroll", Body.fromString(payload)).addHeader(Header.ContentType(MediaType.application.json)),
      )
    yield response

  private def enrol(identity: EdgeIdentityService): ZIO[Client & TestClient & Scope, Throwable, Response] = post(identity)

  private def identityOf(edgeId: EdgeId, group: EdgeGroupId) =
    EdgeIdentityService.Identity(
      "CHAIN",
      EdgeRecord(
        id = edgeId,
        publicKey = Some(signingKey),
        oldPublicKey = None,
        requireDpopNonce = true,
        overloadPolicy = None,
        accessLogPolicy = None,
        rateLimitPolicies = None,
        policyMeta = None,
        groupId = group,
        groupKind = EdgeGroupKind.Partner,
        publicUrl = None,
      ),
    )

  def spec = suite("EdgeEnrollController")(
    test("a valid token is answered with the certificate chain and what the edge needs to start, and no credential is asked for") {
      val identity = stub[EdgeIdentityService]
      for
        _ <- identity.enroll.succeedsWith(identityOf(edgeId, EdgeGroupId("acme")))
        response <- enrol(identity)
        answer <- response.body.asJson[EnrollEdgeResponse]
      yield assertTrue(
        response.status == Status.Ok,
        answer.certificate == "CHAIN",
        answer.edge == EnrollEdgeResponse.Edge(edgeId, EdgeGroupId("acme")),
        answer.central == EnrollEdgeResponse.Central(Some("https://central-mtls.example.com")),
        answer.auth == EnrollEdgeResponse.Auth(Some("https://id.example.com"), Some("https://id-mtls.example.com")),
        identity.enroll.calls == List((Some(edgeId), "TOKEN", "REQUEST", Some(signingKey))),
      )
    },
    test("the edge id is optional: the token says which edge it is, and the public key is optional for an edge that has one") {
      val identity = stub[EdgeIdentityService]
      val payload = """{"token":"TOKEN","csr":"REQUEST"}"""
      for
        _ <- identity.enroll.succeedsWith(identityOf(edgeId, EdgeGroupId("acme")))
        response <- post(identity, payload)
      yield assertTrue(response.status == Status.Ok, identity.enroll.calls == List((None, "TOKEN", "REQUEST", None)))
    },
    test("the old answer is still in the new one: a caller that reads only the certificate keeps working") {
      val identity = stub[EdgeIdentityService]
      for
        _ <- identity.enroll.succeedsWith(identityOf(edgeId, EdgeGroupId("acme")))
        response <- enrol(identity)
        answer <- response.body.asJson[EdgeIdentityResponse]
      yield assertTrue(answer.certificate == "CHAIN")
    },
    test("addresses central has not been told are absent, not invented") {
      val identity = stub[EdgeIdentityService]
      for
        _ <- identity.enroll.succeedsWith(identityOf(edgeId, EdgeGroupId("acme")))
        response <- post(identity, central = TestCentralConfig.config)
        answer <- response.body.asJson[EnrollEdgeResponse]
      yield assertTrue(answer.central.mtlsUrl.isEmpty, answer.auth.mtlsUrl.isEmpty)
    },
    test("a partner that is suspended is 403") {
      val identity = stub[EdgeIdentityService]
      for
        _ <- identity.enroll.failsWith(EdgeIdentityService.PartnerSuspended)
        response <- enrol(identity)
      yield assertTrue(response.status == Status.Forbidden)
    },
    test("an unknown, spent or expired token is 401 with nothing in the body to tell which") {
      val identity = stub[EdgeIdentityService]
      for
        _ <- identity.enroll.failsWith(EdgeIdentityService.InvalidToken)
        response <- enrol(identity)
        text <- response.body.asString
      yield assertTrue(response.status == Status.Unauthorized, text.isEmpty)
    },
    test("a request that names something else is 422 and says why") {
      val identity = stub[EdgeIdentityService]
      for
        _ <- identity.enroll.failsWith(EdgeIdentityService.Refused("the request names another group"))
        response <- enrol(identity)
        text <- response.body.asString
      yield assertTrue(response.status == Status.UnprocessableEntity, text.contains("another group"))
    },
    test("nothing else is served on this listener") {
      val identity = stub[EdgeIdentityService]
      for
        client <- ZIO.service[Client]
        env <- environment(identity)
        _ <- TestClient.addRoutes(Observability.handleErrors(EdgeEnrollController.routes.provideEnvironment(env)))
        sync <- client.batched(Request.get(URL.root / "edge" / "sync" / "clients"))
        admin <- client.batched(Request.get(URL.root / "configuration" / "edges"))
      yield assertTrue(sync.status == Status.NotFound, admin.status == Status.NotFound)
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

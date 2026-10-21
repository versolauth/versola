package versola.central.configuration.edges

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.SignEdgeCertificateResponse
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
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

  private val body = EnrollEdgeRequest(edgeId = edgeId, token = "TOKEN", csr = "REQUEST").toJson

  private def enrol(identity: EdgeIdentityService): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          EdgeEnrollController.routes.provideEnvironment(ZEnvironment[EdgeIdentityService](identity) ++ tracing),
        ),
      )
      response <- client.batched(
        Request.post(URL.root / "edge" / "enroll", Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json)),
      )
    yield response

  def spec = suite("EdgeEnrollController")(
    test("a valid token is answered with the certificate chain, and no credential is asked for") {
      val identity = stub[EdgeIdentityService]
      for
        _ <- identity.enroll.succeedsWith("CHAIN")
        response <- enrol(identity)
        answer <- response.body.asJson[SignEdgeCertificateResponse]
      yield assertTrue(
        response.status == Status.Ok,
        answer.certificate == "CHAIN",
        identity.enroll.calls == List((edgeId, "TOKEN", "REQUEST")),
      )
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
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeEnrollController.routes.provideEnvironment(ZEnvironment[EdgeIdentityService](identity) ++ tracing),
          ),
        )
        sync <- client.batched(Request.get(URL.root / "edge" / "sync" / "clients"))
        admin <- client.batched(Request.get(URL.root / "configuration" / "edges"))
      yield assertTrue(sync.status == Status.NotFound, admin.status == Status.NotFound)
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

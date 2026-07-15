package versola.central.configuration.edges

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestAdminAuth
import versola.central.configuration.clients.OAuthClientService
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object EdgeControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val edgeId = EdgeId("edge-1")

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[EdgeService] => UIO[Unit] = _ => ZIO.unit,
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        edgeService = stub[EdgeService]
        oauthClientService = stub[OAuthClientService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeController.routes.provideEnvironment(
              ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[OAuthClientService](oauthClientService) ++
                tracing
            )
          )
        )
        _ <- oauthClientService.verifySecret.succeedsWith(true)
        _ <- setup(edgeService)
        response <- client.batched(request.addHeader(TestAdminAuth.basicAuthHeader))
      yield assertTrue(response.status == expectedStatus)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("EdgeController")(
    controllerTestCase(
      description = "get all edges returns 200 OK",
      request = Request.get(URL.root / "configuration" / "edges"),
      expectedStatus = Status.Ok,
      setup = service => service.getAllEdges.succeedsWith(Vector.empty),
    ),
    controllerTestCase(
      description = "delete edge returns 204 No Content",
      request = Request.delete(
        (URL.root / "configuration" / "edges").addQueryParam("edgeId", edgeId.toString)
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteEdge.succeedsWith(()),
    ),
  )

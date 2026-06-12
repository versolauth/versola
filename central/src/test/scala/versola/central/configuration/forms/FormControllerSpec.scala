package versola.central.configuration.forms

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.central.configuration.edges.EdgeService
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object FormControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config

  private val form = FormRecord(FormId("credential"), 1, true, "style", Some("src"), Some("compiled"), Map.empty, Vector.empty)

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
      setup: Stub[FormService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[FormService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client     <- ZIO.service[Client]
        service    = stub[FormService]
        edgeService = stub[EdgeService]
        tracing    <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            FormController.routes.provideEnvironment(
              ZEnvironment[FormService](service) ++ tracing ++ ZEnvironment(config) ++ ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        _ <- setup(service)
        response      <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult  <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("FormController")(
    controllerTestCase(
      description = "GET /configuration/forms returns all forms",
      request = Request.get(URL.empty / "configuration" / "forms"),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getAllForms.succeedsWith(Vector(form)),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllFormsResponse]
        yield assertTrue(
          service.getAllForms.calls.length == 1,
          payload == GetAllFormsResponse(Vector(form)),
        ),
    ),
  )

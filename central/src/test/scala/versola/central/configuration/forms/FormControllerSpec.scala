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

  private val en = FormLocale("en", "English")
  private val fr = FormLocale("fr", "French")

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
      description = "GET /configuration/forms/locales returns locales list",
      request = Request.get(URL.empty / "configuration" / "forms" / "locales"),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getLocales.succeedsWith(Vector(en, fr)),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetFormLocalesResponse]
        yield assertTrue(
          service.getLocales.calls.length == 1,
          payload == GetFormLocalesResponse(Vector(en, fr)),
        ),
    ),
    controllerTestCase(
      description = "PUT /configuration/forms/locales updates locales and returns 204",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "forms" / "locales",
        body = Body.fromString(UpdateFormLocalesRequest(add = Vector(fr), delete = Vector("de")).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.updateLocales.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(
          assertTrue(service.updateLocales.calls == List((Vector(fr), Vector("de"))))
        ),
    ),
  )

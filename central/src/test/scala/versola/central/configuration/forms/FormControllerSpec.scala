package versola.central.configuration.forms

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.JWT
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
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

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(config.secretKey),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private val updateFormRequest = UpdateFormRequest(
    id = form.id,
    style = "new-style",
    jsSource = Some("new-src"),
    jsCompiled = Some("new-compiled"),
    localizations = Map("en" -> Map("title" -> "Hello")),
    properties = Vector.empty,
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
        client <- ZIO.service[Client]
        service = stub[FormService]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            FormController.routes.provideEnvironment(
              ZEnvironment[FormService](service) ++ tracing ++ ZEnvironment[CentralConfig](config) ++
                ZEnvironment[EdgeService](edgeService) ++ ZEnvironment[ResourceService](resourceService),
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _ => request
        response <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
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
    controllerTestCase(
      description = "GET /configuration/forms/sync returns active forms for an authorized service token",
      request = Request.get(URL.empty / "configuration" / "forms" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getSyncForms.succeedsWith(Vector(form)),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllFormsResponse]
        yield assertTrue(
          service.getSyncForms.calls.length == 1,
          payload == GetAllFormsResponse(Vector(form)),
        ),
    ),
    controllerTestCase(
      description = "PUT /configuration/forms updates a form without activating it",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "forms",
        body = Body.fromString(updateFormRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.updateForm.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.updateForm.calls == List((
            updateFormRequest.id,
            updateFormRequest.style,
            updateFormRequest.jsSource,
            updateFormRequest.jsCompiled,
            updateFormRequest.localizations,
            updateFormRequest.properties,
            false,
          )),
        )),
    ),
    controllerTestCase(
      description = "PUT /configuration/forms/active sets the active version",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "forms" / "active",
        body = Body.fromString(SetActiveVersionRequest(form.id, 2).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.setActiveVersion.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.setActiveVersion.calls == List((form.id, 2)))),
    ),
  )

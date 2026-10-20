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

object FormTranslationControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config
  private val formId = FormId("otp")
  private val record = FormTranslationRecord(formId = formId, locale = "kk", texts = Map("title" -> "Кодты енгізіңіз"))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val serviceToken = Unsafe.unsafe { unsafe ?=>
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

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[FormTranslationService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[FormTranslationService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[FormTranslationService]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            FormController.routes.provideEnvironment(
              ZEnvironment[FormService](stub[FormService]) ++ ZEnvironment[FormTranslationService](service) ++ tracing ++
                ZEnvironment[CentralConfig](config) ++ ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService),
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

  private val translations = URL.empty / "configuration" / "forms" / "translations"

  def spec = suite("FormController translations")(
    controllerTestCase(
      description = "GET /configuration/forms/translations lists what is stored",
      request = Request.get(translations),
      expectedStatus = Status.Ok,
      setup = service => service.getAll.succeedsWith(Vector(record)),
      verify = (response, _) =>
        for payload <- response.body.asJson[GetFormTranslationsResponse]
        yield assertTrue(payload == GetFormTranslationsResponse(Vector(record))),
    ),
    controllerTestCase(
      description = "GET /configuration/forms/translations refuses a service token, which is not the admin's basic credential",
      request = Request.get(translations).addHeader(Header.Authorization.Bearer(serviceToken)),
      expectedStatus = Status.Unauthorized,
    ),
    controllerTestCase(
      description = "PUT /configuration/forms/translations stores the texts",
      request = Request(
        method = Method.PUT,
        url = translations,
        body = Body.fromString(PutFormTranslationRequest(formId = formId, locale = "kk", texts = record.texts).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.put.succeedsWith(()),
      verify = (_, service) => ZIO.succeed(assertTrue(service.put.calls == List((formId, "kk", record.texts)))),
    ),
    controllerTestCase(
      description = "PUT /configuration/forms/translations answers 400 with the reason when a text does not fit the form",
      request = Request(
        method = Method.PUT,
        url = translations,
        body = Body.fromString(PutFormTranslationRequest(formId = formId, locale = "kk", texts = Map("nonsense" -> "x")).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.put.failsWith(IllegalArgumentException("'nonsense' is not a text of this form")),
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.fromJson[Json] == Right(Json.Obj("message" -> Json.Str("'nonsense' is not a text of this form")))),
    ),
    controllerTestCase(
      description = "PUT /configuration/forms/translations refuses a service token",
      request = Request(
        method = Method.PUT,
        url = translations,
        body = Body.fromString(PutFormTranslationRequest(formId = formId, locale = "kk", texts = record.texts).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)).addHeader(Header.Authorization.Bearer(serviceToken)),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) => ZIO.succeed(assertTrue(service.put.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "DELETE /configuration/forms/translations removes one form's translation into one language",
      request = Request.delete(translations.addQueryParams(QueryParams("formId" -> "otp", "locale" -> "kk"))),
      expectedStatus = Status.NoContent,
      setup = service => service.delete.succeedsWith(()),
      verify = (_, service) => ZIO.succeed(assertTrue(service.delete.calls == List((formId, "kk")))),
    ),
  )

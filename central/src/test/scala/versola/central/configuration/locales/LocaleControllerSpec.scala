package versola.central.configuration.locales

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestCentralConfig
import versola.central.configuration.edges.EdgeService
import versola.util.JWT
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object LocaleControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config
  private val secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES")

  private val en = LocaleRecord("en", "English", isDefault = true, active = true)
  private val ru = LocaleRecord("ru", "Russian", isDefault = false, active = true)

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(secretKey),
        ),
      )
      .getOrThrowFiberFailure()
  }

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
      setup: Stub[LocaleService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[LocaleService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client      <- ZIO.service[Client]
        service     = stub[LocaleService]
        edgeService = stub[EdgeService]
        tracing     <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            LocaleController.routes.provideEnvironment(
              ZEnvironment[LocaleService](service) ++ tracing ++ ZEnvironment(config) ++ ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        _            <- setup(service)
        response     <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("LocaleController")(
    controllerTestCase(
      description = "GET locales returns all locales",
      request = Request.get(URL.empty / "configuration" / "locales"),
      expectedStatus = Status.Ok,
      setup = service => service.getAll.succeedsWith(Vector(en, ru)),
      verify = (response, service) =>
        for payload <- response.body.asJson[GetLocalesResponse]
        yield assertTrue(
          service.getAll.calls.length == 1,
          payload == GetLocalesResponse(Vector(en, ru)),
        ),
    ),
    controllerTestCase(
      description = "PUT locales updates locales and returns no content",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "locales",
        body = Body.fromString(UpdateLocalesRequest(add = Vector(ru), delete = Vector("fr")).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.update.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.update.calls == List((Vector(ru), Vector("fr"))))),
    ),
    controllerTestCase(
      description = "PUT locales/default sets default locale and returns no content",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "locales" / "default",
        body = Body.fromString(SetDefaultLocaleRequest("ru").toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.setDefault.succeedsWith(Right(())),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.setDefault.calls == List("ru"))),
    ),
    controllerTestCase(
      description = "PUT locales/default returns bad request when locale is inactive",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "locales" / "default",
        body = Body.fromString(SetDefaultLocaleRequest("ru").toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service => service.setDefault.succeedsWith(Left(SetDefaultLocaleError.Inactive)),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.setDefault.calls == List("ru"))),
    ),
    controllerTestCase(
      description = "GET locales/sync returns locales and default for authorized service token",
      request = Request
        .get(URL.empty / "configuration" / "locales" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getAll.succeedsWith(Vector(en, ru)),
      verify = (response, service) =>
        for payload <- response.body.asJson[GetLocalesSyncResponse]
        yield assertTrue(
          service.getAll.calls.length == 1,
          payload == GetLocalesSyncResponse(
            locales = Vector(SyncLocaleRecord("en", "English"), SyncLocaleRecord("ru", "Russian")),
            default = "en",
          ),
        ),
    ),
    controllerTestCase(
      description = "reject locales/sync request without service token",
      request = Request.get(URL.empty / "configuration" / "locales" / "sync"),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getAll.calls.isEmpty)),
    ),
  )

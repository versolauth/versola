package versola.central.configuration.system

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.central.configuration.clients.OAuthClientService
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

object SystemSettingsControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config

  private val defaultSettings = SystemSettingsRecord.default
  private val customSettings  = SystemSettingsRecord(
    passwordRegex         = "^(?=.*[A-Z]).{8,}$",
    passwordHistorySize   = 10,
    passwordNumDifferent  = 5,
  )

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(SecretKeySpec(Array.fill(32)(7.toByte), "AES")),
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
      setup: Stub[SystemSettingsService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[SystemSettingsService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client             <- ZIO.service[Client]
        service             = stub[SystemSettingsService]
        edgeService         = stub[EdgeService]
        oauthClientService  = stub[OAuthClientService]
        tracing            <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            SystemSettingsController.routes.provideEnvironment(
              ZEnvironment[SystemSettingsService](service) ++
                tracing ++ ZEnvironment(config) ++
                ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[OAuthClientService](oauthClientService)
            )
          )
        )
        _               <- oauthClientService.verifySecret.succeedsWith(true)
        _               <- setup(service)
        requestWithAuth  = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _    => request
        response        <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult    <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("SystemSettingsController")(
    controllerTestCase(
      description    = "GET returns current settings",
      request        = Request.get(URL.empty / "configuration" / "system-settings"),
      expectedStatus = Status.Ok,
      setup          = svc => svc.getSettings.succeedsWith(defaultSettings),
      verify         = (response, svc) =>
        for payload <- response.body.asJson[SystemSettingsRecord]
        yield assertTrue(
          svc.getSettings.calls.length == 1,
          payload == defaultSettings,
        ),
    ),
    controllerTestCase(
      description    = "GET returns 401 without credentials",
      // Bearer token (not Basic) satisfies the helper's "has Authorization" check
      // but authorizeBasic rejects any non-Basic header
      request        = Request.get(URL.empty / "configuration" / "system-settings")
        .addHeader(Header.Authorization.Bearer("not-basic")),
      expectedStatus = Status.Unauthorized,
      setup          = svc => svc.getSettings.succeedsWith(defaultSettings),
      verify         = (_, svc) => ZIO.succeed(assertTrue(svc.getSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description    = "PUT updates settings and returns 204",
      request        = Request(
        method = Method.PUT,
        url    = URL.empty / "configuration" / "system-settings",
        body   = Body.fromString(customSettings.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup          = svc => svc.upsertSettings.succeedsWith(()),
      verify         = (_, svc) =>
        ZIO.succeed(assertTrue(svc.upsertSettings.calls == List(customSettings))),
    ),
    controllerTestCase(
      description    = "PUT returns 401 without credentials",
      request        = Request(
        method = Method.PUT,
        url    = URL.empty / "configuration" / "system-settings",
        body   = Body.fromString(customSettings.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json))
       .addHeader(Header.Authorization.Bearer("not-basic")),
      expectedStatus = Status.Unauthorized,
      setup          = svc => svc.upsertSettings.succeedsWith(()),
      verify         = (_, svc) => ZIO.succeed(assertTrue(svc.upsertSettings.calls.isEmpty)),
    ),
    controllerTestCase(
      description    = "GET sync returns settings for authorized service token",
      request        = Request
        .get(URL.empty / "configuration" / "system-settings" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup          = svc => svc.getSettings.succeedsWith(customSettings),
      verify         = (response, svc) =>
        for payload <- response.body.asJson[SystemSettingsRecord]
        yield assertTrue(
          svc.getSettings.calls.length == 1,
          payload == customSettings,
        ),
    ),
    controllerTestCase(
      description    = "GET sync returns 401 without service token",
      request        = Request.get(URL.empty / "configuration" / "system-settings" / "sync"),
      expectedStatus = Status.Unauthorized,
      setup          = svc => svc.getSettings.succeedsWith(defaultSettings),
      verify         = (_, svc) => ZIO.succeed(assertTrue(svc.getSettings.calls.isEmpty)),
    ),
  )

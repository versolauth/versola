package versola.central.configuration.clients

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.CentralConfig
import versola.central.configuration.{AuthorizationPresetInput, AuthorizationPresetResponse, SaveAuthorizationPresetsRequest}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{RedirectUri, Secret}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api
import zio.telemetry.opentelemetry.OpenTelemetry

import javax.crypto.spec.SecretKeySpec

object AuthorizationPresetControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val clientId = ClientId("web-app")

  private val preset1 = AuthorizationPreset(
    id = PresetId("web-login"),
    tenantId = tenantId,
    clientId = clientId,
    description = "Web Login",
    redirectUri = RedirectUri("https://example.com/callback"),
    scope = Set(ScopeToken("openid"), ScopeToken("profile")),
    responseType = ResponseType.Code,
    uiLocales = Some(List("en")),
    customParameters = Map.empty,
  )

  private val preset2 = AuthorizationPreset(
    id = PresetId("mobile-login"),
    tenantId = tenantId,
    clientId = clientId,
    description = "Mobile Login",
    redirectUri = RedirectUri("https://example.com/mobile"),
    scope = Set(ScopeToken("openid")),
    responseType = ResponseType.CodeIdToken,
    uiLocales = None,
    customParameters = Map("prompt" -> List("login")),
  )

  private val saveRequest = SaveAuthorizationPresetsRequest(
    tenantId = tenantId,
    clientId = clientId,
    presets = List(
      AuthorizationPresetInput(
        id = PresetId("web-login"),
        description = "Web Login",
        redirectUri = RedirectUri("https://example.com/callback"),
        scope = Set(ScopeToken("openid")),
        responseType = ResponseType.Code,
        uiLocales = Some(List("en")),
        customParameters = Map.empty,
      ),
    ),
  )

  private val config = CentralConfig(
    initialize = false,
    clientSecretsPepper = Secret(Array.fill(16)(5.toByte)),
    secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES"),
  )

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
      setup: Stub[AuthorizationPresetService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[AuthorizationPresetService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[AuthorizationPresetService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          AuthorizationPresetController.routes.provideEnvironment(
            ZEnvironment(service) ++ ZEnvironment(config) ++ tracing
          ).sandbox
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("AuthorizationPresetController")(
    controllerTestCase(
      description = "return client presets",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "auth-request-presets")
          .addQueryParams(Map("tenantId" -> tenantId, "clientId" -> clientId))
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getClientPresets.succeedsWith(Vector(preset1, preset2)),
      verify = (response, service) =>
        for
          body <- response.body.asString
          presets <- ZIO.fromEither(body.fromJson[Vector[AuthorizationPresetResponse]]).mapError(new Exception(_))
        yield assertTrue(
          service.getClientPresets.calls == List((tenantId, clientId)),
          presets == Vector(
            AuthorizationPresetResponse(
              id = preset1.id,
              clientId = preset1.clientId,
              description = preset1.description,
              redirectUri = preset1.redirectUri,
              scope = preset1.scope,
              responseType = preset1.responseType,
              uiLocales = preset1.uiLocales,
              customParameters = preset1.customParameters,
            ),
            AuthorizationPresetResponse(
              id = preset2.id,
              clientId = preset2.clientId,
              description = preset2.description,
              redirectUri = preset2.redirectUri,
              scope = preset2.scope,
              responseType = preset2.responseType,
              uiLocales = preset2.uiLocales,
              customParameters = preset2.customParameters,
            ),
          ),
        ),
    ),
    controllerTestCase(
      description = "return empty array when no presets",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "auth-request-presets")
          .addQueryParams(Map("tenantId" -> tenantId, "clientId" -> clientId))
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getClientPresets.succeedsWith(Vector.empty),
      verify = (response, _) =>
        for
          body <- response.body.asString
          presets <- ZIO.fromEither(body.fromJson[Vector[AuthorizationPresetResponse]]).mapError(new Exception(_))
        yield assertTrue(presets.isEmpty),
    ),
    controllerTestCase(
      description = "save presets successfully",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "auth-request-presets",
        body = Body.fromString(saveRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.savePresets.succeedsWith(Right(())),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.savePresets.calls == List(saveRequest))),
    ),
    controllerTestCase(
      description = "return bad request when client not found",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "auth-request-presets",
        body = Body.fromString(saveRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service =>
        service.savePresets.succeedsWith(Left(PresetValidationError.ClientNotFound)),
    ),
    controllerTestCase(
      description = "return bad request when redirect URI invalid",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "auth-request-presets",
        body = Body.fromString(saveRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service =>
        service.savePresets.succeedsWith(Left(PresetValidationError.InvalidRedirectUri)),
    ),
    controllerTestCase(
      description = "return bad request when scope invalid",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "auth-request-presets",
        body = Body.fromString(saveRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service =>
        service.savePresets.succeedsWith(Left(PresetValidationError.InvalidScope)),
    ),
  )

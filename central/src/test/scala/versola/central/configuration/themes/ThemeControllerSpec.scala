package versola.central.configuration.themes

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestCentralConfig
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestAdminAuth}
import versola.util.JWT
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object ThemeControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantId = TenantId("t1")
  private val themeId = "theme-1"
  private val themeRecord = ThemeRecord(themeId, "body { color: red; }", Some(tenantId))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val config = TestCentralConfig.config

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

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[ThemeService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[ThemeService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        themeService = stub[ThemeService]
        resourceService = stub[ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ThemeController.routes.provideEnvironment(
              ZEnvironment[ThemeService](themeService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[CentralConfig](config) ++
                tracing,
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(themeService)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _ => request
        response <- client.batched(requestWithAuth)
        verifyResult <- verify(response, themeService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("ThemeController")(
    controllerTestCase(
      description = "get themes returns 200 OK",
      request = Request.get(
        (URL.root / "configuration" / "themes").addQueryParam("tenantId", tenantId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getThemes.succeedsWith(Vector(themeRecord)),
    ),
    controllerTestCase(
      description = "delete theme returns 204 No Content",
      request = Request.delete(
        (URL.root / "configuration" / "themes").addQueryParam("id", themeId),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteTheme.succeedsWith(()),
    ),
    controllerTestCase(
      description = "delete theme returns 409 Conflict when the theme is in use",
      request = Request.delete(
        (URL.root / "configuration" / "themes").addQueryParam("id", themeId),
      ),
      expectedStatus = Status.Conflict,
      setup = service => service.deleteTheme.failsWith(new ThemeService.ThemeInUseError),
    ),
    controllerTestCase(
      description = "delete theme returns 400 Bad Request when deleting the default theme",
      request = Request.delete(
        (URL.root / "configuration" / "themes").addQueryParam("id", ThemeService.DefaultThemeId),
      ),
      expectedStatus = Status.BadRequest,
      setup = service => service.deleteTheme.failsWith(new IllegalArgumentException("Cannot delete the default theme")),
    ),
    controllerTestCase(
      description = "create theme returns 201 Created",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString(CreateThemeRequest(themeId, "body { color: green; }", Some(tenantId)).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createTheme.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.createTheme.calls == List(ThemeRecord(themeId, "body { color: green; }", Some(tenantId))),
        )),
    ),
    controllerTestCase(
      description = "update theme returns 204 No Content",
      request = Request(
        method = Method.PUT,
        url = URL.root / "configuration" / "themes",
        body = Body.fromString(UpdateThemeRequest(themeId, "body { color: yellow; }").toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.updateTheme.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.updateTheme.calls == List(ThemeRecord(themeId, "body { color: yellow; }", None)),
        )),
    ),
    controllerTestCase(
      description = "sync themes returns 200 OK for an authorized service token",
      request = Request.get(URL.root / "configuration" / "themes" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getAllThemes.succeedsWith(Vector(themeRecord)),
      verify = (response, service) =>
        for payload <- response.body.asJson[GetAllThemesResponse]
        yield assertTrue(
          service.getAllThemes.calls.length == 1,
          payload == GetAllThemesResponse(Vector(themeRecord)),
        ),
    ),
  )

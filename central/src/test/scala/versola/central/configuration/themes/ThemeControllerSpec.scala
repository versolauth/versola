package versola.central.configuration.themes

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{CentralConfig, TestAdminAuth}
import versola.central.TestCentralConfig
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object ThemeControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantId = TenantId("t1")
  private val themeId  = "theme-1"
  private val themeRecord = ThemeRecord(themeId, "body { color: red; }", Some(tenantId))

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
      setup: Stub[ThemeService] => UIO[Unit] = _ => ZIO.unit,
  ) =
    test(description) {
      for
        client             <- ZIO.service[Client]
        themeService        = stub[ThemeService]
        resourceService  = stub[ResourceService]
        edgeService         = stub[EdgeService]
        tracing            <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ThemeController.routes.provideEnvironment(
              ZEnvironment[ThemeService](themeService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[CentralConfig](TestCentralConfig.config) ++
                tracing
            )
          )
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(themeService)
        response <- client.batched(request.addHeader(TestAdminAuth.basicAuthHeader))
      yield assertTrue(response.status == expectedStatus)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("ThemeController")(
    controllerTestCase(
      description = "get themes returns 200 OK",
      request = Request.get(
        (URL.root / "configuration" / "themes").addQueryParam("tenantId", tenantId.toString)
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getThemes.succeedsWith(Vector(themeRecord)),
    ),
    controllerTestCase(
      description = "delete theme returns 204 No Content",
      request = Request.delete(
        (URL.root / "configuration" / "themes").addQueryParam("id", themeId)
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteTheme.succeedsWith(()),
    ),
  )

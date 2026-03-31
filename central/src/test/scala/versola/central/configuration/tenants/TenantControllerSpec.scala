package versola.central.configuration.tenants

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.*
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object TenantControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId1 = TenantId("tenant-a")
  private val tenantId2 = TenantId("tenant-b")

  private val tenants = Vector(
    TenantRecord(tenantId1, "Tenant A", None),
    TenantRecord(tenantId2, "Tenant B", None),
  )

  private val createRequest = CreateTenantRequest(
    id = tenantId1,
    description = "Tenant A",
    edgeId = None,
  )

  private val updateRequest = UpdateTenantRequest(
    id = tenantId1,
    description = "Updated Tenant A",
    edgeId = None,
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
      setup: Stub[TenantService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[TenantService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[TenantService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            TenantController.routes.provideEnvironment(ZEnvironment(service) ++ tracing)
          )
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("TenantController")(
    controllerTestCase(
      description = "return all tenants",
      request = Request.get(URL.empty / "v1" / "configuration" / "tenants"),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getAllTenants.succeedsWith(tenants),
      verify = (response, _) =>
        for
          payload <- response.body.asJson[GetAllTenantsResponse]
        yield assertTrue(
          payload == GetAllTenantsResponse(
            Vector(
              TenantResponse(tenantId1, "Tenant A", None),
              TenantResponse(tenantId2, "Tenant B", None),
            )
          )
        ),
    ),
    controllerTestCase(
      description = "create tenant",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "tenants",
        body = Body.fromString(createRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service =>
        service.createTenant.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.createTenant.calls == List(createRequest))),
    ),
    controllerTestCase(
      description = "update tenant",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "v1" / "configuration" / "tenants",
        body = Body.fromString(updateRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.updateTenant.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.updateTenant.calls == List(updateRequest))),
    ),
    controllerTestCase(
      description = "delete tenant",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "v1" / "configuration" / "tenants")
          .addQueryParam("tenantId", tenantId1.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.deleteTenant.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteTenant.calls == List(tenantId1))),
    ),
  )
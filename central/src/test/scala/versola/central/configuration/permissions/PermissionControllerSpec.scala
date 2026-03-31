package versola.central.configuration.permissions

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.*
import versola.central.configuration.resources.ResourceEndpointId
import versola.central.configuration.tenants.TenantId
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

object PermissionControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  private val tenantId = TenantId("tenant-a")
  private val usersRead = Permission("users:read")
  private val adminView = Permission("admin:view")
  private val usersReadListEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000101")
  private val usersReadDetailEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000102")
  private val usersReadManagedEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000103")
  private val usersReadEndpointIds = Set(usersReadListEndpointId, usersReadDetailEndpointId)

  private val permissions = Vector(
    PermissionRecord(Some(tenantId), usersRead, Map("en" -> "Read users"), usersReadEndpointIds),
    PermissionRecord(None, adminView, Map("en" -> "View admin panel"), Set.empty),
  )

  private val createRequest = CreatePermissionRequest(
    tenantId = Some(tenantId),
    permission = usersRead,
    description = Map("en" -> "Read users"),
    endpointIds = usersReadEndpointIds,
  )

  private val updateRequest = UpdatePermissionRequest(
    tenantId = Some(tenantId),
    permission = usersRead,
    description = PatchDescription(
      add = Map("ru" -> "Чтение пользователей"),
      delete = Set.empty,
    ),
    endpointIds = Some(usersReadEndpointIds + usersReadManagedEndpointId),
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
      setup: Stub[PermissionService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[PermissionService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[PermissionService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            PermissionController.routes.provideEnvironment(ZEnvironment(service) ++ tracing)
          )
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("PermissionController")(
    controllerTestCase(
      description = "return tenant permissions with pagination params",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "permissions")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "offset" -> "2", "limit" -> "5"))
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantPermissions.succeedsWith(permissions),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllPermissionsResponse]
        yield assertTrue(
          service.getTenantPermissions.calls == List((tenantId, 2, Some(5))),
          payload == GetAllPermissionsResponse(
            Vector(
              PermissionResponse(usersRead, Map("en" -> "Read users"), usersReadEndpointIds),
              PermissionResponse(adminView, Map("en" -> "View admin panel"), Set.empty),
            )
          ),
        ),
    ),
    controllerTestCase(
      description = "use default offset and empty limit when pagination params are absent",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "permissions")
          .addQueryParam("tenantId", tenantId.toString)
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantPermissions.succeedsWith(Vector.empty),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllPermissionsResponse]
        yield assertTrue(
          service.getTenantPermissions.calls == List((tenantId, 0, None)),
          payload == GetAllPermissionsResponse(Vector.empty),
        ),
    ),
    controllerTestCase(
      description = "create permission",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "permissions",
        body = Body.fromString(createRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service =>
        service.createPermission.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.createPermission.calls == List(createRequest))),
    ),
    controllerTestCase(
      description = "update permission",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "v1" / "configuration" / "permissions",
        body = Body.fromString(updateRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.updatePermission.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.updatePermission.calls == List(updateRequest))),
    ),
    controllerTestCase(
      description = "delete global permission when tenantId is absent",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "v1" / "configuration" / "permissions")
          .addQueryParam("permission", adminView.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.deletePermission.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deletePermission.calls == List((None, adminView)))),
    ),
  )
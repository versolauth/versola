package versola.central.configuration.roles

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.*
import versola.central.configuration.permissions.Permission
import versola.central.configuration.tenants.TenantId
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object RoleControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val adminRole = RoleId("admin")
  private val operatorRole = RoleId("operator")
  private val usersRead = Permission("users:read")
  private val usersWrite = Permission("users:write")
  private val sessionsRead = Permission("sessions:read")

  private val roles = Vector(
    RoleRecord(
      id = adminRole,
      tenantId = tenantId,
      description = Map("en" -> "Admin role"),
      permissions = Set(usersRead, usersWrite),
      active = true,
    ),
    RoleRecord(
      id = operatorRole,
      tenantId = tenantId,
      description = Map("en" -> "Operator role"),
      permissions = Set(sessionsRead),
      active = false,
    ),
  )

  private val createRequest = CreateRoleRequest(
    tenantId = tenantId,
    id = adminRole,
    description = Map("en" -> "Admin role"),
    permissions = Set(usersRead, usersWrite),
  )

  private val updateRequest = UpdateRoleRequest(
    tenantId = tenantId,
    id = adminRole,
    description = PatchDescription(
      add = Map("ru" -> "Роль администратора"),
      delete = Set.empty,
    ),
    permissions = PatchPermissions(
      add = Set(sessionsRead),
      remove = Set(usersWrite),
    ),
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
      setup: Stub[RoleService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[RoleService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[RoleService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            RoleController.routes.provideEnvironment(ZEnvironment(service) ++ tracing)
          )
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("RoleController")(
    controllerTestCase(
      description = "return tenant roles with pagination params",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "roles")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "offset" -> "4", "limit" -> "6"))
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantRoles.succeedsWith(roles),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllRolesResponse]
        yield assertTrue(
          service.getTenantRoles.calls == List((tenantId, 4, Some(6))),
          payload == GetAllRolesResponse(
            Vector(
              RoleResponse(adminRole, Map("en" -> "Admin role"), Set(usersRead, usersWrite), active = true),
              RoleResponse(operatorRole, Map("en" -> "Operator role"), Set(sessionsRead), active = false),
            )
          ),
        ),
    ),
    controllerTestCase(
      description = "use default offset and empty limit when pagination params are absent",
      request = Request.get(
        (URL.empty / "v1" / "configuration" / "roles")
          .addQueryParam("tenantId", tenantId.toString)
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantRoles.succeedsWith(Vector.empty),
      verify = (response, service) =>
        for
          payload <- response.body.asJson[GetAllRolesResponse]
        yield assertTrue(
          service.getTenantRoles.calls == List((tenantId, 0, None)),
          payload == GetAllRolesResponse(Vector.empty),
        ),
    ),
    controllerTestCase(
      description = "create role",
      request = Request(
        method = Method.POST,
        url = URL.empty / "v1" / "configuration" / "roles",
        body = Body.fromString(createRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service =>
        service.createRole.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.createRole.calls == List(createRequest))),
    ),
    controllerTestCase(
      description = "update role",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "v1" / "configuration" / "roles",
        body = Body.fromString(updateRequest.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.updateRole.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.updateRole.calls == List(updateRequest))),
    ),
    controllerTestCase(
      description = "delete role",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "v1" / "configuration" / "roles")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "roleId" -> adminRole.toString)),
      ),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.deleteRole.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteRole.calls == List((tenantId, adminRole)))),
    ),
  )
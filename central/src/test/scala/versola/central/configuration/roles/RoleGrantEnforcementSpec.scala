package versola.central.configuration.roles

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.audit.{AuditHook, AuditReportService}
import versola.central.configuration.*
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.permissions.Permission
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.users.{UpdateUserRolesRequest, UserController, UserId, UserService}
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.EnvName
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

/** Nobody grants what they do not have, through the real routes and the real [[RoleGrantGuard]], over stubbed services.
  *
  * The administrator is the one edge names in `actorId` and `actorTenantId`; what they hold is the permissions of their active
  * roles. A call that names no administrator (a service, a client, a deployment whose edge predates the parameters) is not
  * checked, which is what keeps every direct caller of central working.
  */
object RoleGrantEnforcementSpec extends ZIOSpecDefault, ZIOStubs:

  private val actorId = UserId(UUID.fromString("018f0f2a-1c7b-7000-8000-0000000000a1"))
  private val otherUser = UserId(UUID.fromString("018f0f2a-1c7b-7000-8000-0000000000b2"))
  private val actorTenant = TenantId("default")
  private val tenant = TenantId("tenant-a")

  private val usersRead = Permission("users:read")
  private val usersManage = Permission("users:manage")
  private val accessManage = Permission("access:manage")
  private val securityManage = Permission("security:manage")

  private val support = RoleId("support")
  private val viewer = RoleId("viewer")
  private val admin = RoleId("oauth-admin")
  private val ghost = RoleId("ghost")

  /** What the administrator holds: `users:read` and `users:manage`, through their `support` role. */
  private val held = Set(usersRead, usersManage)

  private def role(id: RoleId, permissions: Set[Permission]) =
    RoleRecord(id = id, tenantId = tenant, description = Map.empty, permissions = permissions, active = true)

  private val roles = Map(
    support -> role(support, held),
    viewer -> role(viewer, Set(usersRead)),
    admin -> role(admin, Set(usersManage, accessManage, securityManage)),
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private case class Run(response: Response, body: String, users: Stub[UserService], roleService: Stub[RoleService])

  private def asActor(
      url: URL,
      id: Option[UserId] = Some(actorId),
      tenantId: Option[TenantId] = Some(actorTenant),
      clientId: Option[String] = None,
  ): URL =
    val params = List(
      id.map(AuditHook.ActorIdParam -> _.toString),
      tenantId.map(AuditHook.ActorTenantParam -> _.toString),
      clientId.map(AuditHook.ActorClientParam -> _),
    ).flatten
    url.addQueryParams(params)

  private def run(request: Request): ZIO[Scope, Throwable, Run] =
    (for
      client <- ZIO.service[Client]
      users = stub[UserService]
      roleService = stub[RoleService]
      resources = stub[ResourceService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          (RoleController.routes ++ UserController.routes).provideEnvironment(
            ZEnvironment[UserService](users) ++ ZEnvironment[RoleService](roleService) ++ ZEnvironment[ResourceService](resources) ++
              ZEnvironment[EdgeService](stub[EdgeService]) ++ ZEnvironment[CentralConfig](TestCentralConfig.config) ++
              ZEnvironment[EnvName](EnvName.Test("test")) ++ ZEnvironment[AuditReportService](stub[AuditReportService]) ++ tracing,
          ),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      _ <- users.getRoles.succeedsWith(List(support))
      _ <- users.updateRoles.succeedsWith(())
      _ <- roleService.getActivePermissionsForRoles.succeedsWith(held)
      _ <- roleService.findRole.returnsZIO((_, id) => ZIO.succeed(roles.get(id)))
      _ <- roleService.createRole.succeedsWith(())
      _ <- roleService.updateRole.succeedsWith(())
      _ <- roleService.deleteRole.succeedsWith(())
      response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)).addHeader(TestAdminAuth.basicAuthHeader))
      body <- response.body.asString
    yield Run(response, body, users, roleService)).provideSomeLayer[Scope](TestClient.layer)

  private def patchRoles(
      userId: UserId,
      add: Set[RoleId] = Set.empty,
      remove: Set[RoleId] = Set.empty,
      url: URL = asActor(URL.empty / "users" / "roles"),
  ) =
    Request(
      method = Method.PATCH,
      url = url,
      body = Body.fromString(UpdateUserRolesRequest(userId = userId, tenantId = tenant, add = add, remove = remove).toJson),
    ).addHeader(Header.ContentType(MediaType.application.json))

  private def createRole(permissions: Set[Permission], url: URL = asActor(URL.empty / "configuration" / "roles")) =
    Request(
      method = Method.POST,
      url = url,
      body = Body.fromString(
        CreateRoleRequest(tenantId = tenant, id = RoleId("new-role"), description = Map.empty, permissions = permissions, active = true).toJson,
      ),
    ).addHeader(Header.ContentType(MediaType.application.json))

  private def updateRole(id: RoleId, add: Set[Permission], url: URL = asActor(URL.empty / "configuration" / "roles")) =
    Request(
      method = Method.PUT,
      url = url,
      body = Body.fromString(
        UpdateRoleRequest(
          tenantId = tenant,
          id = id,
          description = PatchDescription(add = Map.empty, delete = Set.empty),
          permissions = PatchPermissions(add = add, remove = Set.empty),
          active = None,
        ).toJson,
      ),
    ).addHeader(Header.ContentType(MediaType.application.json))

  private def deleteRole(id: RoleId, url: URL = asActor(URL.empty / "configuration" / "roles")) =
    Request.delete(url.addQueryParams(List("tenantId" -> tenant.toString, "roleId" -> id.toString)))

  def spec = suite("Granting roles")(
    suite("PATCH /users/roles")(
      test("an administrator gives another user a role that grants only what they hold") {
        for r <- run(patchRoles(otherUser, add = Set(viewer)))
        yield assertTrue(r.response.status == Status.Accepted, r.users.updateRoles.calls.size == 1)
      },
      test("an administrator may not give a role that grants more than they hold, and nothing is written") {
        for r <- run(patchRoles(otherUser, add = Set(admin)))
        yield assertTrue(
          r.response.status == Status.Forbidden,
          r.users.updateRoles.calls.isEmpty,
          r.body.contains("oauth-admin"),
          r.body.contains("access:manage"),
        )
      },
      test("an administrator may not take away a role that grants more than they hold") {
        for r <- run(patchRoles(otherUser, remove = Set(admin)))
        yield assertTrue(r.response.status == Status.Forbidden, r.users.updateRoles.calls.isEmpty)
      },
      test("one role beyond the administrator's among several refuses the whole change") {
        for r <- run(patchRoles(otherUser, add = Set(viewer, admin)))
        yield assertTrue(r.response.status == Status.Forbidden, r.users.updateRoles.calls.isEmpty)
      },
      test("an administrator may not change their own roles, even to a role they could give another") {
        for
          granted <- run(patchRoles(actorId, add = Set(viewer)))
          removed <- run(patchRoles(actorId, remove = Set(support)))
        yield assertTrue(
          granted.response.status == Status.Forbidden,
          removed.response.status == Status.Forbidden,
          granted.users.updateRoles.calls.isEmpty,
          removed.users.updateRoles.calls.isEmpty,
        )
      },
      test("a change that names no role is not a change, for the administrator's own account too") {
        for r <- run(patchRoles(actorId))
        yield assertTrue(r.response.status == Status.Accepted)
      },
      test("a role nobody has defined grants nothing and is not refused") {
        for r <- run(patchRoles(otherUser, add = Set(ghost)))
        yield assertTrue(r.response.status == Status.Accepted)
      },
      test("an administrator whose tenant edge did not name is refused, since what they hold cannot be known") {
        for r <- run(patchRoles(otherUser, add = Set(viewer), url = asActor(URL.empty / "users" / "roles", tenantId = None)))
        yield assertTrue(r.response.status == Status.Forbidden, r.users.updateRoles.calls.isEmpty)
      },
      test("a call that names no administrator is not checked, as every direct caller of central has always been") {
        for r <- run(patchRoles(actorId, add = Set(admin), url = URL.empty / "users" / "roles"))
        yield assertTrue(r.response.status == Status.Accepted, r.users.getRoles.calls.isEmpty)
      },
      test("a client credentials token, whose subject is the client, is not checked") {
        val client = "loadgen-client"
        for
          r <- run(
            Request(
              method = Method.PATCH,
              url = (URL.empty / "users" / "roles").addQueryParams(
                List(
                  AuditHook.ActorIdParam -> client,
                  AuditHook.ActorClientParam -> client,
                  AuditHook.ActorTenantParam -> actorTenant.toString,
                ),
              ),
              body = Body.fromString(UpdateUserRolesRequest(otherUser, tenant, Set(admin), Set.empty).toJson),
            ).addHeader(Header.ContentType(MediaType.application.json)),
          )
        yield assertTrue(r.response.status == Status.Accepted, r.users.getRoles.calls.isEmpty)
      },
    ),
    suite("POST /configuration/roles")(
      test("a role is created with permissions the administrator holds") {
        for r <- run(createRole(Set(usersRead)))
        yield assertTrue(r.response.status == Status.Created, r.roleService.createRole.calls.size == 1)
      },
      test("a role may not be created with a permission the administrator does not hold") {
        for r <- run(createRole(Set(usersRead, accessManage)))
        yield assertTrue(
          r.response.status == Status.Forbidden,
          r.roleService.createRole.calls.isEmpty,
          r.body.contains("access:manage"),
          !r.body.contains("users:read"),
        )
      },
      test("a call that names no administrator creates any role") {
        for r <- run(createRole(Set(accessManage), url = URL.empty / "configuration" / "roles"))
        yield assertTrue(r.response.status == Status.Created)
      },
    ),
    suite("PUT /configuration/roles")(
      test("a role the administrator covers is given a permission they hold") {
        for r <- run(updateRole(viewer, add = Set(usersManage)))
        yield assertTrue(r.response.status == Status.NoContent, r.roleService.updateRole.calls.size == 1)
      },
      test("a role may not be given a permission the administrator does not hold, the role they hold included") {
        for
          other <- run(updateRole(viewer, add = Set(accessManage)))
          own <- run(updateRole(support, add = Set(accessManage)))
        yield assertTrue(
          other.response.status == Status.Forbidden,
          own.response.status == Status.Forbidden,
          other.roleService.updateRole.calls.isEmpty,
          own.roleService.updateRole.calls.isEmpty,
        )
      },
      test("a role that holds more than the administrator may not be changed at all") {
        for r <- run(updateRole(admin, add = Set(usersRead)))
        yield assertTrue(r.response.status == Status.Forbidden, r.roleService.updateRole.calls.isEmpty)
      },
    ),
    suite("DELETE /configuration/roles")(
      test("a role the administrator covers may be deleted") {
        for r <- run(deleteRole(viewer))
        yield assertTrue(r.response.status == Status.NoContent, r.roleService.deleteRole.calls.size == 1)
      },
      test("a role that holds more than the administrator may not be deleted") {
        for r <- run(deleteRole(admin))
        yield assertTrue(r.response.status == Status.Forbidden, r.roleService.deleteRole.calls.isEmpty)
      },
      test("a call that names no administrator deletes any role") {
        for r <- run(deleteRole(admin, url = URL.empty / "configuration" / "roles"))
        yield assertTrue(r.response.status == Status.NoContent)
      },
    ),
    suite("what an administrator holds")(
      test("is asked of the roles in the tenant of their token, through the user's roles") {
        for r <- run(patchRoles(otherUser, add = Set(viewer)))
        yield assertTrue(
          r.users.getRoles.calls == List((actorId, actorTenant)),
          r.roleService.getActivePermissionsForRoles.calls == List((actorTenant, Set(support))),
        )
      },
    ),
  ) @@ TestAspect.silentLogging @@ TestAspect.sequential

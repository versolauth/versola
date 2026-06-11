package versola.central.configuration.roles

import versola.central.{CentralConfig, authorizeInternal}
import versola.central.configuration.{CreateRoleRequest, GetAllRolesResponse, GetRolesSyncResponse, RoleResponse, RoleSyncResponse, UpdateRoleRequest}
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.permissions.Permission
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Cause, ZIO}

object RoleController extends Controller:
  type Env = Tracing & RoleService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    createRoleEndpoint,
    updateRoleEndpoint,
    deleteRoleEndpoint,
    getAllRolesEndpoint,
    syncRolesEndpoint,
  )

  val createRoleEndpoint =
    Method.POST / "configuration" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[RoleService]
        body <- request.body.asJson[CreateRoleRequest]
        role <- service.createRole(body)
      yield Response.status(Status.Created)
    }

  val updateRoleEndpoint =
    Method.PUT / "configuration" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[RoleService]
        body <- request.body.asJson[UpdateRoleRequest]
        _ <- service.updateRole(body)
      yield Response.status(Status.NoContent)
    }

  val deleteRoleEndpoint =
    Method.DELETE / "configuration" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[RoleService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        roleId <- request.url.queryZIO[RoleId]("roleId")
        _ <- service.deleteRole(tenantId, roleId)
      yield Response.status(Status.NoContent)
    }

  val getAllRolesEndpoint =
    Method.GET / "configuration" / "roles" -> handler { (request: Request) =>
      for
        configurationService <- ZIO.service[RoleService]

        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        offset <- request.url.queryZIO[Option[Int]]("offset").someOrElse(0)
        limit <- request.url.queryZIO[Option[Int]]("limit")

        roles <- configurationService.getTenantRoles(tenantId, offset, limit).map(_.map { role =>
          RoleResponse(
            id = role.id,
            description = role.description,
            permissions = role.permissions,
            active = role.active,
          )
        })
      yield Response.json(GetAllRolesResponse(roles).toJson)
    }

  val syncRolesEndpoint =
    Method.GET / "configuration" / "roles" / "sync" -> handler { (request: Request) =>
      for
        service <- ZIO.service[RoleService]
        edgeId <- authorizeInternal(request)
        roles <- service.getRolesForSync(edgeId)
        response = GetRolesSyncResponse(roles.map { role =>
          RoleSyncResponse(
            tenantId = role.tenantId,
            id = role.id,
            permissions = role.permissions,
            active = role.active,
          )
        })
      yield Response.json(response.toJson)
    }

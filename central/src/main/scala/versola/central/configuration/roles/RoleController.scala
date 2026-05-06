package versola.central.configuration.roles

import versola.central.configuration.{CreateRoleRequest, GetAllRolesResponse, RoleResponse, UpdateRoleRequest}
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.permissions.Permission
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Cause, ZIO}

object RoleController extends Controller:
  type Env = Tracing & RoleService

  def routes: Routes[Env, Throwable] = Routes(
    createRoleEndpoint,
    updateRoleEndpoint,
    deleteRoleEndpoint,
    getAllRolesEndpoint,
  )

  val createRoleEndpoint =
    Method.POST / "v1" / "configuration" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[RoleService]
        body <- request.body.asJson[CreateRoleRequest]
        role <- service.createRole(body)
      yield Response.status(Status.Created)
    }

  val updateRoleEndpoint =
    Method.PUT / "v1" / "configuration" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[RoleService]
        body <- request.body.asJson[UpdateRoleRequest]
        _ <- service.updateRole(body)
      yield Response.status(Status.NoContent)
    }

  val deleteRoleEndpoint =
    Method.DELETE / "v1" / "configuration" / "roles" -> handler { (request: Request) =>
      for
        service <- ZIO.service[RoleService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        roleId <- request.url.queryZIO[RoleId]("roleId")
        _ <- service.deleteRole(tenantId, roleId)
      yield Response.status(Status.NoContent)
    }

  val getAllRolesEndpoint =
    Method.GET / "v1" / "configuration" / "roles" -> handler { (request: Request) =>
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

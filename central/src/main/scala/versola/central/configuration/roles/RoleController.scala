package versola.central.configuration.roles

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.authorizeBasic
import versola.central.configuration.permissions.Permission
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateRoleRequest, GetAllRolesResponse, RoleResponse, UpdateRoleRequest}
import versola.central.users.UserService
import zio.http.{Method, Request, Response, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Cause, ZIO}

object RoleController extends AuditedController:
  type Env = Tracing & RoleService & ResourceService & UserService

  def endpoints = List(
    createRoleEndpoint,
    updateRoleEndpoint,
    deleteRoleEndpoint,
    getAllRolesEndpoint,
  )

  val createRoleEndpoint =
    (Method.POST / "configuration" / "roles").audited(Audit.change("role", Audit.create)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[RoleService]
        body <- request.bodyAs[CreateRoleRequest]
        _ <- Audit.target(body.id)
        _ <- Audit.tenant(body.tenantId)
        _ <- RoleGrantGuard.enforce(request)(RoleGrantGuard.checkCreate(_, body.permissions))
        role <- service.createRole(body)
      yield Response.status(Status.Created))
        .catchSome { case denied: RoleGrantGuard.Denied => RoleGrantGuard.refusal(denied) }
    }

  val updateRoleEndpoint =
    (Method.PUT / "configuration" / "roles").audited(Audit.change("role", Audit.update)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[RoleService]
        body <- request.bodyAs[UpdateRoleRequest]
        _ <- Audit.target(body.id)
        _ <- Audit.tenant(body.tenantId)
        _ <- Audit.changed(body, "tenantId", "id")
        _ <- RoleGrantGuard.enforce(request)(RoleGrantGuard.checkUpdate(_, body.tenantId, body.id, body.permissions))
        _ <- service.updateRole(body)
      yield Response.status(Status.NoContent))
        .catchSome { case denied: RoleGrantGuard.Denied => RoleGrantGuard.refusal(denied) }
    }

  val deleteRoleEndpoint =
    (Method.DELETE / "configuration" / "roles").audited(Audit.change("role", Audit.delete)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[RoleService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        _ <- Audit.tenant(tenantId)
        roleId <- request.url.queryZIO[RoleId]("roleId")
        _ <- Audit.target(roleId)
        _ <- RoleGrantGuard.enforce(request)(RoleGrantGuard.checkDelete(_, tenantId, roleId))
        _ <- service.deleteRole(tenantId, roleId)
      yield Response.status(Status.NoContent))
        .catchSome:
          case denied: RoleGrantGuard.Denied => RoleGrantGuard.refusal(denied)
          case error: RoleService.RoleInUseError =>
            ZIO.succeed(Response.json(s"""{"message":"${error.getMessage}"}""").status(Status.Conflict))
    }

  val getAllRolesEndpoint =
    (Method.GET / "configuration" / "roles").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
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

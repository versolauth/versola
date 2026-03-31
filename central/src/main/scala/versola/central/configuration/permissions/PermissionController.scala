package versola.central.configuration.permissions

import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreatePermissionRequest, GetAllPermissionsResponse, PermissionResponse, UpdatePermissionRequest}
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.{Cause, ZIO}

object PermissionController extends Controller:
  type Env = Tracing & PermissionService

  def routes: Routes[Env, Throwable] = Routes(
    getAllPermissionsEndpoint,
    createPermissionEndpoint,
    updatePermissionEndpoint,
    deletePermissionEndpoint,
  )

  val getAllPermissionsEndpoint =
    Method.GET / "v1" / "configuration" / "permissions" -> handler { (request: Request) =>
      for
        service <- ZIO.service[PermissionService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        offset <- request.url.queryZIO[Option[Int]]("offset").someOrElse(0)
        limit <- request.url.queryZIO[Option[Int]]("limit")
        permissions <- service.getTenantPermissions(tenantId, offset, limit).map(_.map { record =>
          PermissionResponse(
            record.id,
            record.description,
            record.endpointIds,
          )
        })
      yield Response.json(GetAllPermissionsResponse(permissions).toJson)
    }

  val createPermissionEndpoint =
    Method.POST / "v1" / "configuration" / "permissions" -> handler { (request: Request) =>
      for
        service <- ZIO.service[PermissionService]
        body <- request.body.asJson[CreatePermissionRequest]
        _ <- service.createPermission(body)
      yield Response.status(Status.Created)
    }

  val updatePermissionEndpoint =
    Method.PUT / "v1" / "configuration" / "permissions" -> handler { (request: Request) =>
      for
        service <- ZIO.service[PermissionService]
        body <- request.body.asJson[UpdatePermissionRequest]
        _ <- service.updatePermission(body)
      yield Response.status(Status.NoContent)
    }

  val deletePermissionEndpoint =
    Method.DELETE / "v1" / "configuration" / "permissions" -> handler { (request: Request) =>
      for
        service <- ZIO.service[PermissionService]
        permission <- request.url.queryZIO[Permission]("permission")
        tenantId <- request.url.queryZIO[Option[TenantId]]("tenantId")
        _ <- service.deletePermission(tenantId, permission)
      yield Response.status(Status.NoContent)
    }

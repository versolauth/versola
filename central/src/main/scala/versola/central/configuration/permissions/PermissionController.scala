package versola.central.configuration.permissions

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreatePermissionRequest, GetAllPermissionsResponse, GetPermissionsSyncResponse, PermissionResponse, PermissionSyncResponse, UpdatePermissionRequest}
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Cause, ZIO}

object PermissionController extends Controller:
  type Env = Tracing & PermissionService & OAuthClientService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllPermissionsEndpoint,
    createPermissionEndpoint,
    updatePermissionEndpoint,
    deletePermissionEndpoint,
    syncPermissionsEndpoint,
  )

  val getAllPermissionsEndpoint =
    Method.GET / "configuration" / "permissions" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
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
    Method.POST / "configuration" / "permissions" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[PermissionService]
        body <- request.body.asJson[CreatePermissionRequest]
        _ <- service.createPermission(body)
      yield Response.status(Status.Created)
    }

  val updatePermissionEndpoint =
    Method.PUT / "configuration" / "permissions" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[PermissionService]
        body <- request.body.asJson[UpdatePermissionRequest]
        _ <- service.updatePermission(body)
      yield Response.status(Status.NoContent)
    }

  val deletePermissionEndpoint =
    Method.DELETE / "configuration" / "permissions" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[PermissionService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        permission <- request.url.queryZIO[Permission]("permission")
        _ <- service.deletePermission(tenantId, permission)
      yield Response.status(Status.NoContent)
    }

  val syncPermissionsEndpoint =
    Method.GET / "configuration" / "permissions" / "sync" -> handler { (request: Request) =>
      for
        service <- ZIO.service[PermissionService]
        edgeId <- authorizeInternal(request)
        permissions <- service.getPermissionsForSync(edgeId)
        response = GetPermissionsSyncResponse(permissions.map { p =>
          PermissionSyncResponse(
            tenantId = p.tenantId,
            id = p.id,
            endpointIds = p.endpointIds,
          )
        })
      yield Response.json(response.toJson)
    }

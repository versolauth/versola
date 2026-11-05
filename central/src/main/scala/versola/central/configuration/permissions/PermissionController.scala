package versola.central.configuration.permissions

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreatePermissionRequest, GetAllPermissionsResponse, PermissionResponse, UpdatePermissionRequest}
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{Cause, ZIO}

object PermissionController extends AuditedController:
  type Env = Tracing & PermissionService & ResourceService

  def endpoints = List(
    getAllPermissionsEndpoint,
    createPermissionEndpoint,
    updatePermissionEndpoint,
    deletePermissionEndpoint,
  )

  val getAllPermissionsEndpoint =
    (Method.GET / "configuration" / "permissions").audited(Audit.Unrecorded) -> handler { (request: Request) =>
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
    (Method.POST / "configuration" / "permissions").audited(Audit.change("permission", Audit.create)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[PermissionService]
          body <- request.bodyAs[CreatePermissionRequest]
          _ <- Audit.target(body.permission)
          _ <- Audit.tenant(body.tenantId)
          _ <- service.createPermission(body)
        yield Response.status(Status.Created)
    }

  val updatePermissionEndpoint =
    (Method.PUT / "configuration" / "permissions").audited(Audit.change("permission", Audit.update)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[PermissionService]
          body <- request.bodyAs[UpdatePermissionRequest]
          _ <- Audit.target(body.permission)
          _ <- Audit.tenant(body.tenantId)
          _ <- Audit.changed(body, "tenantId")
          _ <- service.updatePermission(body)
        yield Response.status(Status.NoContent)
    }

  val deletePermissionEndpoint =
    (Method.DELETE / "configuration" / "permissions").audited(Audit.change("permission", Audit.delete)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[PermissionService]
          tenantId <- request.url.queryZIO[TenantId]("tenantId")
          _ <- Audit.tenant(tenantId)
          permission <- request.url.queryZIO[Permission]("permission")
          _ <- Audit.target(permission)
          _ <- service.deletePermission(tenantId, permission)
        yield Response.status(Status.NoContent)
    }

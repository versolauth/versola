package versola.central.configuration.details

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{
  AuthorizationDetailTypeResponse,
  AuthorizationDetailTypeSyncResponse,
  CreateAuthorizationDetailTypeRequest,
  GetAllAuthorizationDetailTypesResponse,
  GetAuthorizationDetailTypesSyncResponse,
  UpdateAuthorizationDetailTypeRequest,
}
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.Controller
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps

object AuthorizationDetailTypeController extends AuditedController:
  type Env = Tracing & AuthorizationDetailTypeService & ResourceService & CentralConfig & EdgeService

  def endpoints = List(
    getAllTypesEndpoint,
    getAllTypesSyncEndpoint,
    createTypeEndpoint,
    updateTypeEndpoint,
    deleteTypeEndpoint,
  )

  val getAllTypesEndpoint =
    (Method.GET / "configuration" / "authorization-detail-types").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AuthorizationDetailTypeService]

        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        offset <- request.url.queryZIO[Option[Int]]("offset").someOrElse(0)
        limit <- request.url.queryZIO[Option[Int]]("limit")

        types <- service.getTenantTypes(tenantId, offset, limit)
      yield Response.json(GetAllAuthorizationDetailTypesResponse(types.map(toResponse)).toJson)
    }

  val getAllTypesSyncEndpoint =
    (Method.GET / "configuration" / "authorization-detail-types" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[AuthorizationDetailTypeService]
        types <- service.getAllTypes
        response = types.map(record =>
          AuthorizationDetailTypeSyncResponse(record.tenantId, record.`type`, record.schema),
        )
      yield Response.json(GetAuthorizationDetailTypesSyncResponse(response).toJson)
    }

  val createTypeEndpoint =
    (Method.POST / "configuration" / "authorization-detail-types").audited(Audit.change(
      "authorization-detail-type",
      Audit.create,
      Audit.body("type"),
    )) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AuthorizationDetailTypeService]
        body <- request.bodyAs[CreateAuthorizationDetailTypeRequest]
        result <- service.createType(body)
      yield result match
        case Right(_) => Response.status(Status.Created)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val updateTypeEndpoint =
    (Method.PUT / "configuration" / "authorization-detail-types").audited(Audit.change(
      "authorization-detail-type",
      Audit.update,
      Audit.body("type"),
    )) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AuthorizationDetailTypeService]
        body <- request.bodyAs[UpdateAuthorizationDetailTypeRequest]
        result <- service.updateType(body)
      yield result match
        case Right(_) => Response.status(Status.NoContent)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val deleteTypeEndpoint =
    (Method.DELETE / "configuration" / "authorization-detail-types").audited(Audit.change(
      "authorization-detail-type",
      Audit.delete,
      Audit.query("type"),
    )) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[AuthorizationDetailTypeService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        `type` <- request.url.queryZIO[AuthorizationDetailType]("type")
        _ <- service.deleteType(tenantId, `type`)
      yield Response.status(Status.NoContent)
    }

  private def toResponse(record: AuthorizationDetailTypeRecord): AuthorizationDetailTypeResponse =
    AuthorizationDetailTypeResponse(record.`type`, record.description, record.schema)

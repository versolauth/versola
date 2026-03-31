package versola.central.configuration.tenants

import versola.central.configuration.{CreateTenantRequest, GetAllTenantsResponse, TenantResponse, UpdateTenantRequest}
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.{Cause, ZIO}

object TenantController extends Controller:
  type Env = Tracing & TenantService

  def routes: Routes[Env, Throwable] = Routes(
    getAllTenantsEndpoint,
    createTenantEndpoint,
    updateTenantEndpoint,
    deleteTenantEndpoint,
  )

  val getAllTenantsEndpoint =
    Method.GET / "v1" / "configuration" / "tenants" -> handler { (_: Request) =>
      for
        service <- ZIO.service[TenantService]
        tenants <- service.getAllTenants
        response = GetAllTenantsResponse(tenants.map(t => TenantResponse(t.id, t.description, t.edgeId.map(_.toString))))
      yield Response.json(response.toJson)
    }

  val createTenantEndpoint =
    Method.POST / "v1" / "configuration" / "tenants" -> handler { (request: Request) =>
      for
        service <- ZIO.service[TenantService]
        body <- request.body.asJson[CreateTenantRequest]
        _ <- service.createTenant(body)
      yield Response.status(Status.Created)
    }

  val updateTenantEndpoint =
    Method.PUT / "v1" / "configuration" / "tenants" -> handler { (request: Request) =>
      for
        service <- ZIO.service[TenantService]
        body <- request.body.asJson[UpdateTenantRequest]
        _ <- service.updateTenant(body)
      yield Response.status(Status.NoContent)
    }

  val deleteTenantEndpoint =
    Method.DELETE / "v1" / "configuration" / "tenants" -> handler { (request: Request) =>
      for
        service <- ZIO.service[TenantService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        _ <- service.deleteTenant(tenantId)
      yield Response.status(Status.NoContent)
    }

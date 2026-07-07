package versola.central.configuration.scopes

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.{ClaimResponse, CreateScopeRequest, GetAllScopesResponse, ScopeWithClaimsResponse, UpdateScopeRequest}
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.ZIO

object ScopeController extends Controller:
  type Env = Tracing & OAuthScopeService & OAuthClientService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    createScopeEndpoint,
    updateScopeEndpoint,
    deleteScopeEndpoint,
    getAllScopesEndpoint,
    getAllScopesSyncEndpoint,
  )

  val getAllScopesEndpoint =
    Method.GET / "configuration" / "scopes" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        scopeService <- ZIO.service[OAuthScopeService]

        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        offset <- request.url.queryZIO[Option[Int]]("offset").someOrElse(0)
        limit <- request.url.queryZIO[Option[Int]]("limit")

        scopes <- scopeService.getTenantScopes(tenantId, offset, limit)

        response = scopes.map { scope =>
          ScopeWithClaimsResponse(
            scope = scope.id,
            description = scope.description,
            claims = scope.claims.map(claim => ClaimResponse(claim.id, claim.description)),
          )
        }
      yield Response.json(GetAllScopesResponse(response).toJson)
    }

  val getAllScopesSyncEndpoint =
    Method.GET / "configuration" / "scopes" / "sync" -> handler { (request: Request) =>
      for
        scopeService <- ZIO.service[OAuthScopeService]
        _ <- authorizeInternal(request)
        scopes <- scopeService.getAllScopes
        response = scopes.map { scope =>
          ScopeWithClaimsResponse(
            scope = scope.id,
            description = scope.description,
            claims = scope.claims.map(claim => ClaimResponse(claim.id, claim.description)),
          )
        }
      yield Response.json(GetAllScopesResponse(response).toJson)
    }

  val createScopeEndpoint =
    Method.POST / "configuration" / "scopes" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthScopeService]
        body <- request.body.asJson[CreateScopeRequest]
        _ <- service.createScope(body)
      yield Response.status(Status.Created)
    }

  val updateScopeEndpoint =
    Method.PUT / "configuration" / "scopes" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthScopeService]
        body <- request.body.asJson[UpdateScopeRequest]
        _ <- service.updateScope(body)
      yield Response.status(Status.NoContent)
    }

  val deleteScopeEndpoint =
    Method.DELETE / "configuration" / "scopes" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthScopeService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        scopeId <- request.url.queryZIO[ScopeToken]("scopeId")
        _ <- service.deleteScope(tenantId, scopeId)
      yield Response.status(Status.NoContent)
    }


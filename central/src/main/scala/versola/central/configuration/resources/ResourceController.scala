package versola.central.configuration.resources

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateResourceRequest, CreateResourceResponse, GetAllResourcesResponse, GetResourcesSyncResponse, ResourceEndpointResponse, ResourceEndpointSyncResponse, ResourceResponse, ResourceSyncResponse, UpdateResourceRequest}
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{DecoderOps, EncoderOps, JsonDecoder}
import zio.ZIO

object ResourceController extends Controller:
  type Env = Tracing & ResourceService & OAuthClientService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllResourcesEndpoint,
    createResourceRoute,
    updateResourceRoute,
    deleteResourceRoute,
    syncResourcesEndpoint,
  )

  val getAllResourcesEndpoint =
    Method.GET / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        offset <- request.url.queryZIO[Option[Int]]("offset").someOrElse(0)
        limit <- request.url.queryZIO[Option[Int]]("limit")
        resources <- service.getTenantResources(tenantId, offset, limit).map(_.map(toResourceResponse))
      yield Response.json(GetAllResourcesResponse(resources).toJson)
    }

  val createResourceRoute =
    Method.POST / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        body <- decodeJsonBody[CreateResourceRequest](request)
        result <- service.createResource(body)
      yield result match
        case Right(resourceId) => Response.json(CreateResourceResponse(resourceId).toJson).status(Status.Created)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val updateResourceRoute =
    Method.PUT / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        body <- decodeJsonBody[UpdateResourceRequest](request)
        result <- service.updateResource(body)
      yield result match
        case Right(_) => Response.status(Status.NoContent)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val deleteResourceRoute =
    Method.DELETE / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        resourceId <- request.url.queryZIO[ResourceId]("resourceId")
        _ <- service.deleteResource(resourceId)
      yield Response.status(Status.NoContent)
    }

  val syncResourcesEndpoint =
    Method.GET / "configuration" / "resources" / "sync" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ResourceService]
        edgeId <- authorizeInternal(request)
        resources <- service.getResourcesForSync(edgeId)
        response = GetResourcesSyncResponse(resources.map(toResourceSyncResponse))
      yield Response.json(response.toJson)
    }

  private def decodeJsonBody[A: JsonDecoder](request: Request) =
    request.body.asString.flatMap { body =>
      ZIO.fromEither(body.fromJson[A])
        .mapError(message => RuntimeException(s"Failed to decode JSON: $message"))
    }

  private def toResourceResponse(record: ResourceRecord): ResourceResponse =
    ResourceResponse(
      resourceId = record.resourceId,
      resource = record.resource,
      endpoints = record.endpoints.map { endpoint =>
        ResourceEndpointResponse(
          id = endpoint.id,
          method = endpoint.method,
          path = endpoint.path,
          fetchUserInfo = endpoint.fetchUserInfo,
          allow = endpoint.allowExpression,
          inject = endpoint.inject,
        )
      },
    )

  private def toResourceSyncResponse(record: ResourceRecord): ResourceSyncResponse =
    ResourceSyncResponse(
      resourceId = record.resourceId,
      tenantId = record.tenantId,
      resource = record.resource,
      endpoints = record.endpoints.map { endpoint =>
        ResourceEndpointSyncResponse(
          id = endpoint.id,
          method = endpoint.method,
          path = endpoint.path,
          fetchUserInfo = endpoint.fetchUserInfo,
          allow = endpoint.allowExpression,
          inject = endpoint.inject,
        )
      },
    )
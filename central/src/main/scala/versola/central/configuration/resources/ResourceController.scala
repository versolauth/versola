package versola.central.configuration.resources

import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateResourceEndpointRequest, CreateResourceRequest, CreateResourceResponse, GetAllResourcesResponse, ResourceEndpointResponse, ResourceResponse, UpdateResourceEndpointRequest, UpdateResourceRequest}
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{DecoderOps, EncoderOps, JsonDecoder}
import zio.ZIO

object ResourceController extends Controller:
  type Env = Tracing & ResourceService

  def routes: Routes[Env, Throwable] = Routes(
    getAllResourcesEndpoint,
    createResourceRoute,
    updateResourceRoute,
    deleteResourceRoute,
  )

  val getAllResourcesEndpoint =
    Method.GET / "v1" / "configuration" / "resources" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ResourceService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        offset <- request.url.queryZIO[Option[Int]]("offset").someOrElse(0)
        limit <- request.url.queryZIO[Option[Int]]("limit")
        resources <- service.getTenantResources(tenantId, offset, limit).map(_.map(toResourceResponse))
      yield Response.json(GetAllResourcesResponse(resources).toJson)
    }

  val createResourceRoute =
    Method.POST / "v1" / "configuration" / "resources" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ResourceService]
        body <- decodeJsonBody[CreateResourceRequest](request)
        id <- service.createResource(body)
      yield Response.json(CreateResourceResponse(id).toJson).status(Status.Created)
    }

  val updateResourceRoute =
    Method.PUT / "v1" / "configuration" / "resources" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ResourceService]
        body <- decodeJsonBody[UpdateResourceRequest](request)
        _ <- service.updateResource(body)
      yield Response.status(Status.NoContent)
    }

  val deleteResourceRoute =
    Method.DELETE / "v1" / "configuration" / "resources" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ResourceService]
        id <- request.url.queryZIO[ResourceId]("id")
        _ <- service.deleteResource(id)
      yield Response.status(Status.NoContent)
    }

  private def decodeJsonBody[A: JsonDecoder](request: Request) =
    request.body.asString.flatMap { body =>
      ZIO.fromEither(body.fromJson[A])
        .mapError(message => RuntimeException(s"Failed to decode JSON: $message"))
    }

  private def toResourceResponse(record: ResourceRecord): ResourceResponse =
    ResourceResponse(
      id = record.id,
      resource = record.resource,
      endpoints = record.endpoints.map { endpoint =>
        ResourceEndpointResponse(
          id = endpoint.id,
          method = endpoint.method,
          path = endpoint.path,
          fetchUserInfo = endpoint.fetchUserInfo,
          allowRules = endpoint.allowRules,
          denyRules = endpoint.denyRules,
          injectHeaders = endpoint.injectHeaders,
        )
      },
    )
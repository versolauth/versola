package versola.central.configuration.metadata

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

object ServerMetadataController extends Controller:
  type Env = ResourceService & CentralConfig & EdgeService & ServerMetadataService

  def routes: Routes[Env, Throwable] = Routes(
    getMetadataEndpoint,
    upsertMetadataEndpoint,
    getMetadataSyncEndpoint,
  )

  val getMetadataEndpoint =
    Method.GET / "configuration" / "server-metadata" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ServerMetadataService]
        metadata <- service.getMetadata
      yield Response.json(metadata.fold[Json](Json.Null)(identity).toJson)
    }

  val upsertMetadataEndpoint =
    Method.POST / "configuration" / "server-metadata" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ServerMetadataService]
        metadata <- request.bodyAs[Json.Obj]
        _ <- service.upsertMetadata(metadata)
      yield Response.status(Status.NoContent)
    }

  val getMetadataSyncEndpoint =
    Method.GET / "configuration" / "server-metadata" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[ServerMetadataService]
        metadata <- service.getMetadata
      yield Response.json(metadata.getOrElse(Json.Obj()).toJson)
    }

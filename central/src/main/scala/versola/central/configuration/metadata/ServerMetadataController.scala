package versola.central.configuration.metadata

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

object ServerMetadataController extends AuditedController:
  type Env = ResourceService & CentralConfig & EdgeService & ServerMetadataService

  def endpoints = List(
    getMetadataEndpoint,
    upsertMetadataEndpoint,
    getMetadataSyncEndpoint,
  )

  val getMetadataEndpoint =
    (Method.GET / "configuration" / "server-metadata").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ServerMetadataService]
        metadata <- service.getMetadata
      yield Response.json(metadata.fold[Json](Json.Null)(identity).toJson)
    }

  val upsertMetadataEndpoint =
    (Method.POST / "configuration" / "server-metadata").audited(Audit.change("server-metadata", Audit.create, noTarget = true)) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          service <- ZIO.service[ServerMetadataService]
          metadata <- request.bodyAs[Json.Obj]
          _ <- service.upsertMetadata(metadata)
        yield Response.status(Status.NoContent)
    }

  val getMetadataSyncEndpoint =
    (Method.GET / "configuration" / "server-metadata" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[ServerMetadataService]
        metadata <- service.getMetadata
      yield Response.json(metadata.getOrElse(Json.Obj()).toJson)
    }

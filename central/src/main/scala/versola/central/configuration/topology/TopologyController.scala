package versola.central.configuration.topology

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.{EdgeId, EdgeService}
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.{Observability, Unauthorized}
import zio.http.{Method, Request, Response, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.{IO, ZIO}

/** The topology an operator registers (#557): partitions, regions, cells and the region of an edge site. Read with
  * `edges:read` and changed with `edges:manage`, like the edges and their groups it describes.
  */
object TopologyController extends AuditedController:
  type Env = Tracing & TopologyService & EdgeService & CentralConfig & ResourceService

  def endpoints = List(
    getTopologyEndpoint,
    createPartitionEndpoint,
    createRegionEndpoint,
    deleteRegionEndpoint,
    createCellEndpoint,
    updateCellEndpoint,
    deleteCellEndpoint,
    setEdgeRegionEndpoint,
    cellsSyncEndpoint,
  )

  val getTopologyEndpoint =
    (Method.GET / "configuration" / "topology").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        topology <- ZIO.serviceWithZIO[TopologyService](_.get)
        response = TopologyResponse(partitions = topology.partitions, regions = topology.regions, cells = topology.cells)
      yield Response.json(response.toJson)
    }

  val createPartitionEndpoint =
    (Method.POST / "configuration" / "partitions").audited(Audit.change("partition", Audit.create)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[PartitionRecord]
        _ <- Audit.target(body.id)
        response <- answered(Status.Created)(ZIO.serviceWithZIO[TopologyService](_.createPartition(body)))
      yield response
    }

  val createRegionEndpoint =
    (Method.POST / "configuration" / "regions").audited(Audit.change("region", Audit.create)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[RegionRecord]
        _ <- Audit.target(body.id)
        response <- answered(Status.Created)(ZIO.serviceWithZIO[TopologyService](_.createRegion(body)))
      yield response
    }

  val deleteRegionEndpoint =
    (Method.DELETE / "configuration" / "regions").audited(Audit.change("region", Audit.delete)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        regionId <- request.url.queryZIO[RegionId]("regionId")
        _ <- Audit.target(regionId)
        response <- answered(Status.NoContent)(ZIO.serviceWithZIO[TopologyService](_.deleteRegion(regionId)))
      yield response
    }

  val createCellEndpoint =
    (Method.POST / "configuration" / "cells").audited(Audit.change("cell", Audit.create)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[CellRecord]
        _ <- Audit.target(body.id)
        response <- answered(Status.Created)(ZIO.serviceWithZIO[TopologyService](_.createCell(body)))
      yield response
    }

  val updateCellEndpoint =
    (Method.PUT / "configuration" / "cells").audited(Audit.change("cell", Audit.update)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[CellRecord]
        _ <- Audit.target(body.id)
        _ <- Audit.changed(body, "id")
        response <- answered(Status.NoContent)(ZIO.serviceWithZIO[TopologyService](_.updateCell(body)))
      yield response
    }

  val deleteCellEndpoint =
    (Method.DELETE / "configuration" / "cells").audited(Audit.change("cell", Audit.delete)) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        cellId <- request.url.queryZIO[CellId]("cellId")
        _ <- Audit.target(cellId)
        response <- answered(Status.NoContent)(ZIO.serviceWithZIO[TopologyService](_.deleteCell(cellId)))
      yield response
    }

  /** Where one of the operator's own edge sites is. A body with no `regionId` takes the edge out of any region. */
  val setEdgeRegionEndpoint =
    (Method.PUT / "configuration" / "edges" / "region").audited(Audit.change("edge", Audit.action("region"))) -> handler {
      (request: Request) =>
        for
          _ <- authorizeBasic(request)
          edgeId <- request.url.queryZIO[EdgeId]("edgeId")
          body <- request.bodyAs[SetEdgeRegionRequest]
          _ <- Audit.target(edgeId)
          _ <- Audit.changed(body)
          response <- answered(Status.NoContent)(ZIO.serviceWithZIO[TopologyService](_.setEdgeRegion(edgeId, body.regionId)))
        yield response
    }

  /** The cells and their regions as auth reads them: a cell needs its own record and, from Phase 2 of #547, the others
    * to place a new user and to hand a sign-in to its home cell. Internal like the other `sync` routes: auth's token
    * only, an edge reads the same list on `/edge/sync/cells`.
    */
  val cellsSyncEndpoint =
    (Method.GET / "configuration" / "cells" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        callerEdgeId <- authorizeInternal(request)
        _ <- ZIO.fail(Unauthorized).when(callerEdgeId.isDefined)
        topology <- ZIO.serviceWithZIO[TopologyService](_.get)
      yield Response.json(CellsSyncResponse.of(topology, region = None).toJson)
    }

  private def answered[R](success: Status)(change: ZIO[R, Throwable | TopologyError, Unit]): ZIO[R, Throwable, Response] =
    change.as(Response.status(success)).catchAll:
      case error: TopologyError => refusal(error)
      case failure: Throwable => ZIO.fail(failure)

  private def refusal(error: TopologyError): IO[Nothing, Response] =
    val (code, status) = error match
      case _: TopologyError.Invalid => ("topology_invalid", Status.BadRequest)
      case _: TopologyError.Missing => ("topology_missing", Status.NotFound)
      case _: TopologyError.Conflict => ("topology_conflict", Status.Conflict)
    Observability.setError(code, Some(error.message)).as(Response.text(error.message).status(status))

case class TopologyResponse(
    partitions: Vector[PartitionRecord],
    regions: Vector[RegionRecord],
    cells: Vector[CellRecord],
) derives Schema, JsonCodec

case class SetEdgeRegionRequest(
    regionId: Option[RegionId],
) derives Schema, JsonCodec

/** A cell as an edge or a cell is told of it: the record, with the partition its region is in. */
case class CellSyncRecord(
    id: CellId,
    regionId: RegionId,
    partitionId: PartitionId,
    internalUrl: String,
    mtlsUrl: Option[String],
    trustedCertificates: List[String],
    state: CellState,
    weight: Int,
) derives Schema, JsonCodec

/** @param region the region of the edge that asked, which is where it looks for an open cell first; `None` for an
  *               edge in no region and for auth
  */
case class CellsSyncResponse(
    cells: Vector[CellSyncRecord],
    region: Option[RegionId],
) derives Schema, JsonCodec

object CellsSyncResponse:
  def of(topology: Topology, region: Option[RegionId]): CellsSyncResponse =
    val partitionOf = topology.regions.map(record => record.id -> record.partitionId).toMap
    CellsSyncResponse(
      cells = topology.cells.flatMap: cell =>
        partitionOf.get(cell.regionId).map: partition =>
          CellSyncRecord(
            id = cell.id,
            regionId = cell.regionId,
            partitionId = partition,
            internalUrl = cell.internalUrl,
            mtlsUrl = cell.mtlsUrl,
            trustedCertificates = cell.trustedCertificates,
            state = cell.state,
            weight = cell.weight,
          ),
      region = region,
    )

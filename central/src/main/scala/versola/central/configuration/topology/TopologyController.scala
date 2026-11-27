package versola.central.configuration.topology

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.edges.{EdgeId, EdgeService}
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeAuth, authorizeBasic}
import versola.util.http.{Observability, Unauthorized}
import zio.http.{Method, Request, Response, Status, handler}
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.{IO, ZIO}

import java.time.Instant

/** The topology an operator registers (#557): partitions, regions, cells and the region of an edge site. Read with
  * `edges:read` and changed with `edges:manage`, like the edges and their groups it describes.
  */
object TopologyController extends AuditedController:
  type Env = Tracing & TopologyService & EdgeService & CellRegistry & CentralConfig & ResourceService

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

  /** The cells as an `auth` knows them (#1050): where each is, whether it takes new users, and the public keys it signs its calls
    * with, which are what a cell seals a sign-in for another with and opens one with. Internal: an `auth` of a registered cell
    * or the configured one, with its token; an edge is refused (its own list is `/edge/sync/cells`, which has no keys).
    * Answered from the cells central holds in memory ([[CellRegistry]]), a change of which is seen at once on the replica that
    * made it and at the next refresh on the others.
    */
  val cellsSyncEndpoint =
    (Method.GET / "configuration" / "cells" / "sync").audited(Audit.Internal) -> handler { (request: Request) =>
      for
        _ <- authorizeAuth(request)
        cells <- ZIO.serviceWithZIO[CellRegistry](_.all)
      yield Response.json(AuthCellsSyncResponse.of(cells).toJson)
    }

  val getTopologyEndpoint =
    (Method.GET / "configuration" / "topology").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        topology <- ZIO.serviceWithZIO[TopologyService](_.get)
        statuses <- ZIO.serviceWithZIO[TopologyService](_.cellStatuses)
        response = TopologyResponse(
          partitions = topology.partitions,
          regions = topology.regions,
          cells = topology.cells.map(cell => CellResponse.of(cell, statuses.get(cell.id))),
        )
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
        // `lost=true`: the cell is gone for good with the users it held (see `TopologyService.deleteCell`).
        lost = request.url.queryParams.getAll("lost").headOption.contains("true")
        // `force=true`: delete although the cell still answers `moved` for refresh tokens, which this makes invalid.
        force = request.url.queryParams.getAll("force").headOption.contains("true")
        _ <- Audit.target(cellId)
        response <- answered(Status.NoContent)(ZIO.serviceWithZIO[TopologyService](_.deleteCell(cellId, lost, force)))
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
    cells: Vector[CellResponse],
) derives Schema, JsonCodec

/** A cell as the console reads it: the record, and what the cell's users and moves add to it (versolauth/versola#1051).
  *
  * @param users how many users the directory places in the cell
  * @param moving how many moves that leave the cell are unfinished
  * @param tokens refresh tokens of moved users the cell still answers `moved` for, from the cell's own report; absent when the
  *   cell did not answer
  * @param tokensUntil when the last of those tokens expires; absent with `tokens`, or when there are none
  * @param drainBlocked set on a draining cell that no open cell of its partition can take users from (`no_open_cell`)
  */
case class CellResponse(
    id: CellId,
    regionId: RegionId,
    internalUrl: String,
    mtlsUrl: Option[String],
    trustedCertificates: List[String],
    state: CellState,
    weight: Int,
    publicKeys: List[Json.Obj],
    users: Long,
    moving: Long,
    tokens: Option[Long],
    tokensUntil: Option[Instant],
    drainBlocked: Option[String],
) derives Schema, JsonCodec

object CellResponse:
  def of(cell: CellRecord, status: Option[CellStatus]): CellResponse =
    CellResponse(
      id = cell.id,
      regionId = cell.regionId,
      internalUrl = cell.internalUrl,
      mtlsUrl = cell.mtlsUrl,
      trustedCertificates = cell.trustedCertificates,
      state = cell.state,
      weight = cell.weight,
      publicKeys = cell.publicKeys,
      users = status.fold(0L)(_.users),
      moving = status.fold(0L)(_.moving),
      tokens = status.flatMap(_.departures).map(_.tokens),
      tokensUntil = status.flatMap(_.departures).flatMap(_.tokensUntil),
      drainBlocked = status.flatMap(_.drainBlocked),
    )

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

/** A cell as an `auth` is told of it (`GET /configuration/cells/sync`, #1050): the state, the weight and the region, and the public
  * RSA JWKs its `auth` signs with. No address and no certificate: an `auth` does not call another. */
case class AuthCellRecord(
    id: CellId,
    state: CellState,
    weight: Int,
    regionId: RegionId,
    publicKeys: List[Json.Obj],
) derives JsonCodec

case class AuthCellsSyncResponse(
    cells: Vector[AuthCellRecord],
) derives JsonCodec

object AuthCellsSyncResponse:
  def of(cells: Vector[CellRecord]): AuthCellsSyncResponse =
    AuthCellsSyncResponse(
      cells = cells.map(cell =>
        AuthCellRecord(id = cell.id, state = cell.state, weight = cell.weight, regionId = cell.regionId, publicKeys = cell.publicKeys),
      ),
    )

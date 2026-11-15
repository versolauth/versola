package versola.central.configuration.topology

import versola.central.configuration.edges.{EdgeGroupKind, EdgeId, EdgeService}
import zio.http.{Scheme, URL}
import zio.{IO, Task, URLayer, ZIO, ZLayer}

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import scala.util.Try

/** Why a change to the topology was refused. */
enum TopologyError(val message: String):
  /** The request names something that cannot be: `400`. */
  case Invalid(reason: String) extends TopologyError(reason)

  /** There is no such partition, region, cell or edge: `404`. */
  case Missing(reason: String) extends TopologyError(reason)

  /** The request is well formed and what is stored does not allow it: `409`. */
  case Conflict(reason: String) extends TopologyError(reason)

/** Everything central knows of the topology, as the console and the sync read it in one piece. */
case class Topology(
    partitions: Vector[PartitionRecord],
    regions: Vector[RegionRecord],
    cells: Vector[CellRecord],
    edgeRegions: Map[EdgeId, RegionId],
)

/** Partitions, regions and cells, and the region of an edge site (#557). The rules of what may be stored live here:
  * the tables hold whatever they are given.
  */
trait TopologyService:
  def get: Task[Topology]

  def createPartition(record: PartitionRecord): IO[Throwable | TopologyError, Unit]

  /** In a partition that exists. */
  def createRegion(record: RegionRecord): IO[Throwable | TopologyError, Unit]

  /** Only a region with no cell and no edge site in it. */
  def deleteRegion(id: RegionId): IO[Throwable | TopologyError, Unit]

  /** In a region that exists, with well-formed addresses and certificates and a weight that is not negative. */
  def createCell(record: CellRecord): IO[Throwable | TopologyError, Unit]

  /** Everything but the id and the region: a cell stays where its database is. */
  def updateCell(record: CellRecord): IO[Throwable | TopologyError, Unit]

  /** Only a `closed` cell: one that takes new users, or is still being emptied, holds users. */
  def deleteCell(id: CellId): IO[Throwable | TopologyError, Unit]

  /** The region of one of the operator's own edges; `None` takes it out of any region. */
  def setEdgeRegion(edgeId: EdgeId, regionId: Option[RegionId]): IO[Throwable | TopologyError, Unit]

object TopologyService:
  val live: URLayer[TopologyRepository & EdgeService, TopologyService] =
    ZLayer.fromFunction(Impl(_, _))

  /** What a cell's record has to satisfy whatever is stored already, in the order an operator would fix it. */
  private[topology] def problems(cell: CellRecord): Option[String] =
    List(
      Option.when(cell.weight < 0)("weight must not be negative"),
      address("internalUrl", cell.internalUrl, httpsOnly = false),
      cell.mtlsUrl.flatMap(address("mtlsUrl", _, httpsOnly = true)),
      cell.trustedCertificates.zipWithIndex.collectFirst:
        case (pem, index) if !isCertificate(pem) => s"trustedCertificates[$index] is not a PEM certificate"
      ,
      Option.when(cell.trustedCertificates.distinct.size != cell.trustedCertificates.size)("trustedCertificates names a certificate twice"),
    ).flatten.headOption

  private def address(name: String, value: String, httpsOnly: Boolean): Option[String] =
    URL.decode(value).toOption match
      case Some(url) if url.host.exists(_.nonEmpty) && url.scheme.contains(Scheme.HTTPS) => None
      case Some(url) if url.host.exists(_.nonEmpty) && url.scheme.contains(Scheme.HTTP) && !httpsOnly => None
      case _ if httpsOnly => Some(s"$name must be an absolute https URL")
      case _ => Some(s"$name must be an absolute http or https URL")

  private def isCertificate(pem: String): Boolean =
    pem.contains("-----BEGIN CERTIFICATE-----") &&
      Try(CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))).isSuccess

  class Impl(repository: TopologyRepository, edges: EdgeService) extends TopologyService:
    override def get: Task[Topology] =
      for
        partitions <- repository.getPartitions
        regions <- repository.getRegions
        cells <- repository.getCells
        edgeRegions <- repository.getEdgeRegions
      yield Topology(partitions = partitions, regions = regions, cells = cells, edgeRegions = edgeRegions)

    override def createPartition(record: PartitionRecord): IO[Throwable | TopologyError, Unit] =
      repository.createPartition(record).flatMap: created =>
        ZIO.fail(TopologyError.Conflict(s"partition '${record.id}' already exists")).unless(created).unit

    override def createRegion(record: RegionRecord): IO[Throwable | TopologyError, Unit] =
      for
        partitions <- repository.getPartitions
        _ <- ZIO.fail(TopologyError.Invalid(s"partition '${record.partitionId}' does not exist"))
          .unless(partitions.exists(_.id == record.partitionId))
        created <- repository.createRegion(record)
        _ <- ZIO.fail(TopologyError.Conflict(s"region '${record.id}' already exists")).unless(created)
      yield ()

    override def deleteRegion(id: RegionId): IO[Throwable | TopologyError, Unit] =
      for
        topology <- get
        _ <- ZIO.fail(TopologyError.Missing(s"region '$id' does not exist")).unless(topology.regions.exists(_.id == id))
        cells = topology.cells.filter(_.regionId == id).map(_.id)
        sites = topology.edgeRegions.collect { case (edge, region) if region == id => edge }.toList.sorted
        _ <- ZIO.fail(TopologyError.Conflict(s"region '$id' still has cells in it: ${cells.mkString(", ")}")).when(cells.nonEmpty)
        _ <- ZIO.fail(TopologyError.Conflict(s"region '$id' still has edges in it: ${sites.mkString(", ")}")).when(sites.nonEmpty)
        _ <- repository.deleteRegion(id)
      yield ()

    override def createCell(record: CellRecord): IO[Throwable | TopologyError, Unit] =
      for
        _ <- ZIO.foreachDiscard(problems(record))(reason => ZIO.fail(TopologyError.Invalid(reason)))
        regions <- repository.getRegions
        _ <- ZIO.fail(TopologyError.Invalid(s"region '${record.regionId}' does not exist")).unless(regions.exists(_.id == record.regionId))
        created <- repository.createCell(record)
        _ <- ZIO.fail(TopologyError.Conflict(s"cell '${record.id}' already exists")).unless(created)
      yield ()

    override def updateCell(record: CellRecord): IO[Throwable | TopologyError, Unit] =
      for
        _ <- ZIO.foreachDiscard(problems(record))(reason => ZIO.fail(TopologyError.Invalid(reason)))
        cells <- repository.getCells
        current <- ZIO.fromOption(cells.find(_.id == record.id)).orElseFail(TopologyError.Missing(s"cell '${record.id}' does not exist"))
        _ <-
          ZIO.fail(TopologyError.Invalid(s"cell '${record.id}' is in region '${current.regionId}' and stays there: a cell is where its database is"))
            .when(current.regionId != record.regionId)
        updated <- repository.updateCell(record)
        _ <- ZIO.fail(TopologyError.Missing(s"cell '${record.id}' does not exist")).unless(updated)
      yield ()

    override def deleteCell(id: CellId): IO[Throwable | TopologyError, Unit] =
      for
        cells <- repository.getCells
        current <- ZIO.fromOption(cells.find(_.id == id)).orElseFail(TopologyError.Missing(s"cell '$id' does not exist"))
        _ <- ZIO.fail(TopologyError.Conflict(s"cell '$id' is ${current.state.code}: only a closed cell is deleted"))
          .when(current.state != CellState.Closed)
        _ <- repository.deleteCell(id)
      yield ()

    override def setEdgeRegion(edgeId: EdgeId, regionId: Option[RegionId]): IO[Throwable | TopologyError, Unit] =
      for
        edge <- edges.findFresh(edgeId).someOrFail(TopologyError.Missing(s"edge '$edgeId' does not exist"))
        _ <- ZIO.fail(TopologyError.Invalid(s"edge '$edgeId' is a partner's: only the operator's own edge sites are in a region"))
          .when(edge.groupKind != EdgeGroupKind.FirstParty)
        regions <- repository.getRegions
        _ <- ZIO.foreachDiscard(regionId): region =>
          ZIO.fail(TopologyError.Invalid(s"region '$region' does not exist")).unless(regions.exists(_.id == region))
        updated <- repository.setEdgeRegion(edgeId, regionId)
        _ <- ZIO.fail(TopologyError.Missing(s"edge '$edgeId' does not exist")).unless(updated)
      yield ()

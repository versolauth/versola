package versola.central.configuration.topology

import versola.central.CentralConfig
import versola.central.configuration.edges.{EdgeGroupKind, EdgeId, EdgeRecord, EdgeService, EdgeSigningKey}
import versola.central.users.{AuthClient, CellDrain, UserMoveRepository, UserRepository}
import zio.http.{Scheme, URL}
import zio.json.ast.Json
import zio.{IO, Task, URLayer, ZIO, ZLayer, durationInt}

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

/** What the cells' own state adds to their records in the topology answer (versolauth/versola#1051).
  *
  * @param users how many users the directory places in the cell
  * @param moving the moves leaving the cell that are not finished
  * @param departures what the cell says of its departures and of the refresh tokens it still answers `moved` for; `None` when it
  *   did not answer in time, which is not an error of the request
  * @param drainBlocked why a draining cell cannot be emptied now ([[CellDrain.NoOpenCell]])
  */
case class CellStatus(users: Long, moving: Long, departures: Option[AuthClient.DeparturesSummary], drainBlocked: Option[String])

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

  /** In a region that exists, with well-formed addresses and certificates, a weight that is not negative and the key or two
    * keys the cell's `auth` signs with, each valid and in no other cell, edge or the configured `auth` (#1050). */
  def createCell(record: CellRecord): IO[Throwable | TopologyError, Unit]

  /** Everything but the id and the region: a cell stays where its database is. The keys are replaced, not added to: the
    * request carries the one or two the cell is to have from now on. */
  def updateCell(record: CellRecord): IO[Throwable | TopologyError, Unit]

  /** Only a `closed` cell that the directory places no user in: one that takes new users, or is still being emptied, holds
    * users, and a closed one may still hold some that were not moved out.
    *
    * @param lost the cell is gone for good and its users with it (its database was lost, a stand is taken down): it is
    *   deleted although the directory still places users in it. Those users stay in the directory, held by a cell that is
    *   not registered, so their identifiers stay taken and nothing can sign them in until they are deleted. Never the
    *   way to retire a cell that works: that is moving its users out. The state rule holds either way, and the two rules below
    *   are skipped.
    *
    * Refused as well while a move from or to the cell is unfinished, and while the cell reports that it still answers `moved` for
    * refresh tokens of users that were moved out of it (or does not answer, which says nothing): the edge does not know a deleted
    * cell, so those tokens would stop working. `force` deletes it anyway and makes those refresh tokens invalid.
    */
  def deleteCell(id: CellId, lost: Boolean, force: Boolean): IO[Throwable | TopologyError, Unit]

  /** The status of every cell: its users, its unfinished moves, what it reports of its departures, and why a draining one is stuck. */
  def cellStatuses: Task[Map[CellId, CellStatus]]

  /** The region of one of the operator's own edges; `None` takes it out of any region. */
  def setEdgeRegion(edgeId: EdgeId, regionId: Option[RegionId]): IO[Throwable | TopologyError, Unit]

object TopologyService:
  val live: URLayer[
    TopologyRepository & EdgeService & UserRepository & CentralConfig & CellRegistry & UserMoveRepository & AuthClient,
    TopologyService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))

  /** How long a cell is given to report its departures before the topology answer goes without them. */
  val SummaryTimeout = 3.seconds

  /** How many keys a cell is registered with: the one it signs with, and a second while the first is being replaced. */
  val MaxCellKeys = 2

  /** Ids no cell is registered under: `auth` is what the configured `auth` is known as while it has no cell id, and `central` is
    * the audience of the tokens an `auth` sends. A cell with either would be indistinguishable from them in a token's header or a log
    * line. */
  val ReservedCellIds: Set[String] = Set("auth", "central")

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

  /** What a cell's keys have to satisfy against what is registered elsewhere (#1050). Central decides who calls by the key that
    * verifies the token, so a key two parties hold would make one of them pass for the other.
    *
    * The configured `auth`'s keys are the cell's own when `auth.cell-id` names this very cell: that `auth` is the cell, and
    * registering it is how it comes to be known as one. For any other cell they are another party's and refused.
    *
    * @param others every cell but this one
    * @param configured `auth.public-keys`
    * @param configuredCell `auth.cell-id`
    * @param edges every registered edge, whose keys verify the tokens that carry an `edge_id`
    */
  private[topology] def keyProblems(
      cell: CellRecord,
      others: Vector[CellRecord],
      configured: List[Json.Obj],
      configuredCell: Option[CellId],
      edges: Vector[EdgeRecord],
  ): Option[String] =
    val keys = cell.publicKeys
    lazy val valid: List[Either[String, Json.Obj]] =
      keys.zipWithIndex.map((key, index) => EdgeSigningKey.validate(key, s"publicKeys[$index]"))
    lazy val prints = keys.map(EdgeSigningKey.thumbprint)
    lazy val kids = keys.flatMap(key => key.fields.collectFirst { case ("kid", Json.Str(kid)) => kid })
    lazy val configuredIsThisCell = configuredCell.contains(cell.id)
    lazy val configuredPrints = if configuredIsThisCell then Nil else configured.map(EdgeSigningKey.thumbprint)
    lazy val configuredKids =
      if configuredIsThisCell then Nil else configured.flatMap(key => key.fields.collectFirst { case ("kid", Json.Str(kid)) => kid })
    List(
      Option.when(keys.isEmpty)("publicKeys must name at least one key: a cell with none cannot call central"),
      Option.when(
        keys.size > MaxCellKeys,
      )(s"publicKeys names ${keys.size} keys, at most $MaxCellKeys: the second is only for as long as a key is being replaced"),
      valid.collectFirst { case Left(reason) => reason },
      Option.when(kids.distinct.size != kids.size)("publicKeys names a kid twice"),
      Option.when(prints.distinct.size != prints.size)("publicKeys names a key twice"),
      others.collectFirst:
        case other if other.publicKeys.exists(known => prints.contains(EdgeSigningKey.thumbprint(known))) =>
          s"a key of publicKeys is registered for cell '${other.id}'"
      ,
      others.collectFirst:
        case other if other.publicKeys.exists(known => known.fields.collectFirst { case ("kid", Json.Str(kid)) => kid }.exists(kids.contains)) =>
          s"a kid of publicKeys is used by a key of cell '${other.id}'"
      ,
      Option.when(prints.exists(configuredPrints.contains))("a key of publicKeys is one of the keys central is configured with for its auth"),
      Option.when(kids.exists(configuredKids.contains))("a kid of publicKeys is the kid of a key central is configured with for its auth"),
      edges.collectFirst:
        case edge if (edge.publicKey.toVector ++ edge.oldPublicKey.toVector).exists(known => prints.contains(EdgeSigningKey.thumbprint(known))) =>
          s"a key of publicKeys is registered for edge '${edge.id}'",
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

  class Impl(
      repository: TopologyRepository,
      edges: EdgeService,
      users: UserRepository,
      config: CentralConfig,
      registry: CellRegistry,
      moves: UserMoveRepository,
      auth: AuthClient,
  ) extends TopologyService:
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
        _ <- ZIO.fail(TopologyError.Invalid(s"cell id '${record.id}' is reserved")).when(ReservedCellIds.contains(record.id))
        _ <- ZIO.foreachDiscard(problems(record))(reason => ZIO.fail(TopologyError.Invalid(reason)))
        regions <- repository.getRegions
        _ <- ZIO.fail(TopologyError.Invalid(s"region '${record.regionId}' does not exist")).unless(regions.exists(_.id == record.regionId))
        _ <- checkKeys(record)
        created <- repository.createCell(record)
        _ <- ZIO.fail(TopologyError.Conflict(s"cell '${record.id}' already exists")).unless(created)
        // seen by the next call of its auth, not at the next refresh
        _ <- registry.refreshNow
      yield ()

    override def updateCell(record: CellRecord): IO[Throwable | TopologyError, Unit] =
      for
        _ <- ZIO.foreachDiscard(problems(record))(reason => ZIO.fail(TopologyError.Invalid(reason)))
        cells <- repository.getCells
        current <- ZIO.fromOption(cells.find(_.id == record.id)).orElseFail(TopologyError.Missing(s"cell '${record.id}' does not exist"))
        _ <-
          ZIO.fail(TopologyError.Invalid(s"cell '${record.id}' is in region '${current.regionId}' and stays there: a cell is where its database is"))
            .when(current.regionId != record.regionId)
        _ <- checkKeys(record)
        updated <- repository.updateCell(record)
        _ <- ZIO.fail(TopologyError.Missing(s"cell '${record.id}' does not exist")).unless(updated)
        _ <- registry.refreshNow
      yield ()

    /** Against the stored cells, as they are now rather than as a cache last saw them (a key registered a moment ago is not to be
      * registered again), and the registered edges. */
    private def checkKeys(record: CellRecord): IO[Throwable | TopologyError, Unit] =
      for
        cells <- repository.getCells
        registered <- edges.getAllEdges
        _ <- ZIO.foreachDiscard(keyProblems(record, cells.filter(_.id != record.id), config.auth.publicKeys, config.auth.cellId, registered)):
          reason =>
            ZIO.fail(TopologyError.Invalid(reason))
      yield ()

    override def deleteCell(id: CellId, lost: Boolean, force: Boolean): IO[Throwable | TopologyError, Unit] =
      for
        cells <- repository.getCells
        current <- ZIO.fromOption(cells.find(_.id == id)).orElseFail(TopologyError.Missing(s"cell '$id' does not exist"))
        _ <- ZIO.fail(TopologyError.Conflict(s"cell '$id' is ${current.state.code}: only a closed cell is deleted"))
          .when(current.state != CellState.Closed)
        held <- if lost then ZIO.succeed(0L) else users.countInCell(id)
        _ <- ZIO.fail(TopologyError.Conflict(s"cell '$id' still holds $held user(s) in the directory: they are moved out first"))
          .when(held > 0)
        _ <- ZIO.unless(lost)(checkNoMovesOrTokens(id, force))
        _ <- repository.deleteCell(id)
        // its keys stop verifying at once
        _ <- registry.refreshNow
      yield ()

    private def checkNoMovesOrTokens(id: CellId, force: Boolean): IO[Throwable | TopologyError, Unit] =
      for
        involved <- moves.involvesUnfinished(id)
        _ <- ZIO.fail(TopologyError.Conflict(s"cell '$id' is part of a move of a user that is not finished: wait for it"))
          .when(involved)
        _ <- ZIO.unless(force):
          summaryOf(id).flatMap:
            case None =>
              ZIO.fail(
                TopologyError.Conflict(s"cell '$id' did not say whether it still answers for refresh tokens of moved users: try again, or force=true"),
              )
            case Some(summary) if summary.tokens > 0 =>
              ZIO.fail(
                TopologyError.Conflict(
                  s"cell '$id' still answers for ${summary.tokens} refresh token(s) of users moved out of it: force=true deletes it and makes them invalid",
                ),
              )
            case Some(_) => ZIO.unit
      yield ()

    /** What the cell says of its departures; `None` when it does not answer in [[TopologyService.SummaryTimeout]]. The failure is
      * deliberately not the request's: it is logged, and the caller decides what the absence means. */
    private def summaryOf(cell: CellId): Task[Option[AuthClient.DeparturesSummary]] =
      auth.departuresSummary(cell).timeout(TopologyService.SummaryTimeout).catchAllCause: cause =>
        ZIO.logWarningCause(s"cell '$cell' did not report its departures", cause).as(None)

    override def cellStatuses: Task[Map[CellId, CellStatus]] =
      for
        cells <- repository.getCells
        regions <- repository.getRegions
        moving <- moves.unfinishedFromCounts
        statuses <- ZIO.foreachPar(cells): cell =>
          for
            held <- users.countInCell(cell.id)
            summary <- summaryOf(cell.id)
          yield cell.id -> CellStatus(
            users = held,
            moving = moving.getOrElse(cell.id, 0L),
            departures = summary,
            drainBlocked = CellDrain.blocked(cell, cells, regions),
          )
      yield statuses.toMap

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

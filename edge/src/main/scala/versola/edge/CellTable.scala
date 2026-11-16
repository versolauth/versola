package versola.edge

import versola.util.{CacheSource, ReloadingCache, RoutingKey}
import zio.http.{Client, ClientSSLConfig, Header, Request, URL}
import zio.json.{JsonCodec, JsonDecoder}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Scope, Task, UIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.util.HexFormat

/** Whether new users are placed in a cell (versolauth/versola#547). A cell in any state still serves the users it holds. */
enum EdgeCellState:
  case Open, Closed, Draining

/** A cell as this edge reaches it: `auth` with its own database, in one region.
  *
  * @param internalUrl where the cell's token, PAR and userinfo calls go
  * @param mtlsUrl the cell's mutual-TLS listener; `None` for a cell without one
  * @param trust the cell's pinned leaf certificates, the only thing its endpoints are authenticated by; `None` when it pins none
  */
final case class EdgeCell(
    id: String,
    regionId: String,
    partitionId: String,
    internalUrl: URL,
    mtlsUrl: Option[URL],
    trust: Option[ClientSSLConfig],
    state: EdgeCellState,
    weight: Int,
)

/** The cells of the deployment and the region this edge is in. Empty for an installation that registered none: the edge
  * then reaches the one `auth` its configuration names, as it always did. */
final case class CellTopology(cells: Vector[EdgeCell], region: Option[String])

object CellTopology:
  val empty: CellTopology = CellTopology(Vector.empty, None)

/** Where a call goes, as far as the edge can tell before it is made. */
enum CellRouting:
  /** The call presents an opaque value (a code, a refresh token): the routing key in front of it names the cell. A value
    * without a key was issued before cells existed and belongs to the deployment the edge is configured for. */
  case ByValue(presented: String)

  /** The call presents an access token: the `cell` header of the token names it, and a token without one is the same
    * legacy case. */
  case ByHeader(cell: Option[String])

  /** Nothing yet says where the user is (a new authorization request): an open cell of this edge's region, by weight. */
  case Placement

/** Why the edge refuses a call before sending it. */
sealed abstract class CellRoutingError(message: String) extends RuntimeException(message)

object CellRoutingError:
  final case class Unknown(cell: String) extends CellRoutingError(s"the value names cell '$cell', which this deployment does not have")
  final case class Malformed(reason: String) extends CellRoutingError(s"the value announces a routing key and does not spell one: $reason")

object CellRouting:

  /** The cell a call goes to; `None` is the deployment the edge is configured for.
    *
    * @param roll a number in `[0, 1)` that picks among the open cells by weight, so the choice is a function
    */
  def route(topology: CellTopology, routing: CellRouting, roll: Double): Either[CellRoutingError, Option[EdgeCell]] =
    if topology.cells.isEmpty then Right(None)
    else
      routing match
        case CellRouting.ByValue(presented) =>
          RoutingKey.decode(presented).left.map(CellRoutingError.Malformed(_)).flatMap(decoded => named(topology, decoded.cell.map(identity[String])))
        case CellRouting.ByHeader(cell) => named(topology, cell)
        case CellRouting.Placement => Right(place(topology, roll))

  private def named(topology: CellTopology, id: Option[String]): Either[CellRoutingError, Option[EdgeCell]] =
    id match
      case None => Right(None)
      case Some(id) => topology.cells.find(_.id == id).map(Some(_)).toRight(CellRoutingError.Unknown(id))

  /** An open cell with a weight, of this edge's region when it has one, by weight; `None` when no cell takes users. */
  private def place(topology: CellTopology, roll: Double): Option[EdgeCell] =
    val takers = topology.cells.filter(cell => cell.state == EdgeCellState.Open && cell.weight > 0)
    val local = takers.filter(cell => topology.region.contains(cell.regionId))
    val pool = if local.nonEmpty then local else takers
    val total = pool.map(_.weight.toLong).sum
    if total == 0 then None
    else
      val target = math.min(math.max(roll, 0.0), 0.999999999) * total
      pool
        .scanLeft((None: Option[EdgeCell], 0L)) { case ((_, upTo), cell) => (Some(cell), upTo + cell.weight) }
        .collectFirst { case (Some(cell), upTo) if target < upTo => cell }

/** The cells this edge syncs from central (`/edge/sync/cells`). */
trait CellsSyncClient extends CacheSource[CellTopology]

object CellsSyncClient:
  val live: zio.URLayer[Client & EdgeConfig & CentralSyncTokenService, CellsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  /** For a partner's edge, which central tells no cells: it keeps one address for the issuer. */
  val none: zio.ULayer[CellsSyncClient] =
    ZLayer.succeed[CellsSyncClient](new CellsSyncClient { override def getAll: Task[CellTopology] = ZIO.succeed(CellTopology.empty) })

  case class Impl(httpClient: Client, config: EdgeConfig, centralSyncTokenService: CentralSyncTokenService) extends CellsSyncClient:
    private val CellsURL = config.syncUrl("cells")

    override def getAll: Task[CellTopology] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(CellsURL).addHeader(Header.Authorization.Bearer(token))
        body <- ZIO.scoped(centralSyncTokenService.syncRequest(httpClient, request).flatMap(_.bodyAs[CellsResponse]))
        cells <- ZIO.foreach(body.cells.toVector)(toCell)
      yield CellTopology(cells, body.region)

    private def toCell(cell: CellResponse): Task[EdgeCell] =
      for
        internal <- url(cell.id, "internalUrl", cell.internalUrl)
        mtls <- ZIO.foreach(cell.mtlsUrl)(url(cell.id, "mtlsUrl", _))
        trust <- ZIO.attemptBlocking(CellsSyncClient.pinned(cell.trustedCertificates))
        state <- ZIO.fromOption(CellsSyncClient.states.get(cell.state))
          .orElseFail(RuntimeException(s"central answered cell '${cell.id}' in the unknown state '${cell.state}'"))
      yield EdgeCell(cell.id, cell.regionId, cell.partitionId, internal, mtls, trust, state, cell.weight)

    private def url(cell: String, name: String, value: String): Task[URL] =
      ZIO.fromEither(URL.decode(value)).mapError(error => RuntimeException(s"cell '$cell' has an invalid $name: ${error.getMessage}"))

  private[edge] val states: Map[String, EdgeCellState] =
    Map("open" -> EdgeCellState.Open, "closed" -> EdgeCellState.Closed, "draining" -> EdgeCellState.Draining)

  /** The trust for a cell's pins: the PEMs central sent, written once to a file named by their digest (so a refresh that
    * changes nothing writes nothing) that is removed on exit, since zio-http reads trust from a path. */
  private[edge] def pinned(pems: List[String]): Option[ClientSSLConfig] =
    Option.when(pems.nonEmpty):
      val bundle = pems.sorted.mkString("\n").getBytes(StandardCharsets.UTF_8)
      val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bundle))
      val path: Path = Path.of(System.getProperty("java.io.tmpdir"), s"versola-cell-pins-$digest.pem")
      if !Files.exists(path) then
        Files.write(path, bundle, StandardOpenOption.CREATE_NEW)
        path.toFile.deleteOnExit()
      ClientSSLConfig.FromCertFile(path.toString)

  private case class CellResponse(
      id: String,
      regionId: String,
      partitionId: String,
      internalUrl: String,
      mtlsUrl: Option[String],
      trustedCertificates: List[String],
      state: String,
      weight: Int,
  ) derives JsonCodec

  private case class CellsResponse(cells: List[CellResponse], region: Option[String]) derives JsonCodec

/** The cells as the edge last synced them. */
trait CellTable:
  def current: UIO[CellTopology]

  def refreshNow: Task[Unit]

object CellTable:
  /** No cell: every call goes to the configured `auth`. */
  val empty: CellTable = new CellTable:
    override def current: UIO[CellTopology] = ZIO.succeed(CellTopology.empty)
    override def refreshNow: Task[Unit] = ZIO.unit

  def live: ZLayer[CellsSyncClient & Scope & EdgeConfig, Throwable, CellTable] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[CellTopology](config.configurationCacheRefreshInterval, fromSnapshot = true),
        )
      ) ++ ZLayer.service[CellsSyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(cache: ReloadingCache[CellTopology], source: CellsSyncClient) extends CellTable:
    override def current: UIO[CellTopology] = cache.get
    override def refreshNow: Task[Unit] = source.getAll.flatMap(cache.set)

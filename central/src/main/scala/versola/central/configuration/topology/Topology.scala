package versola.central.configuration.topology

import versola.util.RoutingKey
import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.*

/** One jurisdiction (versolauth/versola#547 §4): its own issuer, keys and directory. A deployment has one. */
type PartitionId = PartitionId.Type

object PartitionId:
  opaque type Type <: String = String

  inline def apply(string: String): PartitionId = string

  def from(string: String): Either[String, PartitionId] =
    if TopologyId.matches(string) then Right(string) else Left(s"Partition ID ${TopologyId.rule}")

  given Schema[PartitionId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[PartitionId] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[PartitionId] = JsonEncoder.string.contramap(identity[String])

/** One metro inside a partition, a failure domain: a cell and an edge site are each in one region. */
type RegionId = RegionId.Type

object RegionId:
  opaque type Type <: String = String

  inline def apply(string: String): RegionId = string

  def from(string: String): Either[String, RegionId] =
    if TopologyId.matches(string) then Right(string) else Left(s"Region ID ${TopologyId.rule}")

  given Schema[RegionId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[RegionId] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[RegionId] = JsonEncoder.string.contramap(identity[String])

/** The id of a cell, as it is written in front of the opaque values the cell issues (`v1.<cell>.`, #558). Opaque:
  * nothing reads a partition or a region out of it, both come from the cell's record.
  */
type CellId = CellId.Type

object CellId:
  opaque type Type <: String = String

  inline def apply(string: String): CellId = string

  /** The rule is the routing key's own ([[RoutingKey.CellId]]), so a registered cell can always be written in front of a value. */
  def from(string: String): Either[String, CellId] =
    RoutingKey.CellId.parse(string).map(_ => string).left.map(reason => s"Cell ID: $reason")

  given Schema[CellId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[CellId] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[CellId] = JsonEncoder.string.contramap(identity[String])

/** The one shape the three ids share, which is the shape of a routing key's cell id: it can never contain the
  * separator of a routing key and reads the same in a URL, a header and a log line.
  */
private[topology] object TopologyId:
  val MaxLength = 64
  val rule = s"must be lowercase letters, digits and hyphens, neither starting nor ending with a hyphen, at most $MaxLength characters"
  private val shape = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?".r

  def matches(string: String): Boolean = string.length <= MaxLength && shape.matches(string)

/** Whether new users are placed in a cell. A cell in any state still serves the users it holds. */
enum CellState(val code: String):
  /** Takes new users, by its weight among the open cells of its region. */
  case Open extends CellState("open")

  /** Takes no new users. The state a cell has to be in before it is deleted. */
  case Closed extends CellState("closed")

  /** Takes no new users and is being emptied: its users are moved to other cells. */
  case Draining extends CellState("draining")

object CellState:
  def from(code: String): Either[String, CellState] =
    values.find(_.code == code).toRight(s"unknown cell state '$code', expected one of ${values.map(_.code).mkString(", ")}")

  given Schema[CellState] = Schema.primitive[String].transformOrFail(from, state => Right(state.code))
  given JsonDecoder[CellState] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[CellState] = JsonEncoder.string.contramap(_.code)

case class PartitionRecord(
    id: PartitionId,
) derives Schema, JsonCodec

case class RegionRecord(
    id: RegionId,
    partitionId: PartitionId,
) derives Schema, JsonCodec

/** A cell as an operator registers it: where it is, how an edge reaches its auth, and its share of new users.
  *
  * @param internalUrl the address an edge sends the cell's token, PAR and userinfo calls to
  * @param mtlsUrl the cell's mutual-TLS listener, for the calls that present a client certificate; `None` for a
  *                cell without one
  * @param trustedCertificates the leaf certificates (PEM) the cell's endpoints are pinned by; several, so that a
  *                            certificate can be replaced without a gap
  * @param weight the cell's share of new users among the open cells of its region; `0` takes none
  * @param publicKeys the public RSA JWKs the cell's `auth` signs its calls to central with (#1050): one, or two while a
  *                   key is being replaced. Central takes a call to be the cell's when the key that verifies its token is
  *                   here, so a key is in no other cell, no edge and not the configured `auth`
  */
case class CellRecord(
    id: CellId,
    regionId: RegionId,
    internalUrl: String,
    mtlsUrl: Option[String],
    trustedCertificates: List[String],
    state: CellState,
    weight: Int,
    publicKeys: List[Json.Obj],
) derives Schema, JsonCodec

/** Where a user nobody placed yet is created (versolauth/versola#547, D8): a weighted choice over the open cells, so
  * that adding a cell is a change of weights and no user moves. An edge makes the same choice for a registration it
  * takes, among the cells of its own region; this is the one central makes for a user an administrator creates, which
  * comes from no region.
  */
object CellPlacement:

  /** @param roll a number in `[0, 1)`
    * @return `None` when no cell takes users: there is none, or every one is closed, draining or weightless
    */
  def pick(cells: Vector[CellRecord], roll: Double): Option[CellId] =
    val takers = cells.filter(cell => cell.state == CellState.Open && cell.weight > 0)
    val total = takers.map(_.weight.toLong).sum
    if total == 0 then None
    else
      val target = math.min(math.max(roll, 0.0), 0.999999999) * total
      takers
        .scanLeft((None: Option[CellId], 0L)) { case ((_, upTo), cell) => (Some(cell.id), upTo + cell.weight) }
        .collectFirst { case (Some(cell), upTo) if target < upTo => cell }

package versola.central.configuration.partners

import zio.json.ast.Json
import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.*

import java.time.Instant

type ApprovalId = ApprovalId.Type

object ApprovalId:
  opaque type Type <: String = String

  inline def apply(string: String): ApprovalId = string

  given Schema[ApprovalId] = Schema.primitive[String]
  given JsonDecoder[ApprovalId] = JsonDecoder.string
  given JsonEncoder[ApprovalId] = JsonEncoder.string.contramap(identity[String])

/** What a partner asked the operator for (#549 decision 7). */
enum ApprovalKind(val code: String):
  /** A new client: always, whatever it asks for. */
  case ClientCreate extends ApprovalKind("client_create")

  /** A change to an approved client that touches its name or logo (what the consent screen shows) or goes outside the
    * partner's limits. */
  case ClientChange extends ApprovalKind("client_change")

  /** The partner's own name and logo. */
  case ProfileChange extends ApprovalKind("profile_change")

object ApprovalKind:
  /** The requests about a client of the partner. A request about the partner itself (`ProfileChange`) names the partner's id as its
    * target, which is another namespace than the clients': a client may be called as the partner is. */
  val aboutClients: Set[ApprovalKind] = Set(ClientCreate, ClientChange)

  val aboutPartner: Set[ApprovalKind] = Set(ProfileChange)

  def from(code: String): Either[String, ApprovalKind] =
    values.find(_.code == code).toRight(s"unknown approval kind '$code', expected one of ${values.map(_.code).mkString(", ")}")

  given Schema[ApprovalKind] = Schema.primitive[String].transformOrFail(from, kind => Right(kind.code))
  given JsonDecoder[ApprovalKind] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[ApprovalKind] = JsonEncoder.string.contramap(_.code)

enum ApprovalStatus(val code: String):
  case Waiting extends ApprovalStatus("waiting")
  case Approved extends ApprovalStatus("approved")
  case Rejected extends ApprovalStatus("rejected")
  case Withdrawn extends ApprovalStatus("withdrawn")

object ApprovalStatus:
  def from(code: String): Either[String, ApprovalStatus] =
    values.find(_.code == code).toRight(s"unknown approval status '$code', expected one of ${values.map(_.code).mkString(", ")}")

  given Schema[ApprovalStatus] = Schema.primitive[String].transformOrFail(from, status => Right(status.code))
  given JsonDecoder[ApprovalStatus] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[ApprovalStatus] = JsonEncoder.string.contramap(_.code)

/** One request of a partner and the operator's decision on it.
  *
  * @param targetId      the client (`ClientCreate`, `ClientChange`) or the partner (`ProfileChange`)
  * @param requested     the fields as the partner sent them; never rewritten, so what was asked for stays on record
  * @param current       the fields as registered before a change; none for a creation
  * @param applied       what the operator registered when approving with overrides; none when approved as asked, or not yet
  * @param outsideLimits the field names that needed the operator: the invariant's violations, and for a client always
  *                      `clientName` and `logoUri`
  * @param requestedBy   the `sub` of the partner's staff member, from the token the edge forwarded
  * @param decidedBy     the `sub` of the operator's administrator, when decided
  */
case class PartnerApprovalRecord(
    id: ApprovalId,
    partnerId: PartnerId,
    kind: ApprovalKind,
    targetId: String,
    requested: Json.Obj,
    current: Option[Json.Obj],
    applied: Option[Json.Obj],
    outsideLimits: List[String],
    requestedBy: String,
    requestedAt: Instant,
    status: ApprovalStatus,
    decidedBy: Option[String],
    decidedAt: Option[Instant],
    note: Option[String],
) derives Schema, CanEqual

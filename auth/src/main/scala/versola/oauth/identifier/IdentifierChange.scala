package versola.oauth.identifier

import versola.oauth.client.model.Acr
import versola.util.{Email, Phone}
import zio.json.*

/** Which identifier of a user is being changed. The kind is chosen by a reserved `acr_values`
  * value, so a flow can never change an identifier the request did not name.
  */
enum IdentifierChangeKind derives CanEqual:
  case Phone, Email

  def acr: Acr = this match
    case Phone => IdentifierChangeKind.PhoneAcr
    case Email => IdentifierChangeKind.EmailAcr

object IdentifierChangeKind:
  val PhoneAcr: Acr = Acr("urn:versola:acr:phone-change")
  val EmailAcr: Acr = Acr("urn:versola:acr:email-change")

  val ReservedAcrs: Set[Acr] = Set(PhoneAcr, EmailAcr)

  def fromAcr(acr: Acr): Option[IdentifierChangeKind] =
    if acr == PhoneAcr then Some(Phone)
    else if acr == EmailAcr then Some(Email)
    else None

  def fromParameter(value: String): Option[IdentifierChangeKind] = value match
    case "phone" => Some(Phone)
    case "email" => Some(Email)
    case _ => None

  given JsonCodec[IdentifierChangeKind] = JsonCodec.string.transformOrFail(
    value => fromParameter(value).toRight(s"unknown identifier kind: $value"),
    {
      case Phone => "phone"
      case Email => "email"
    },
  )

/** The change a conversation is carrying out.
  *
  * `credential` is the new identifier. While the user is still proving the old one the stage is
  * [[IdentifierChangeStage.Pending]]; once the code was sent to the new identifier it is
  * [[IdentifierChangeStage.VerifyNew]]. A finished change is removed from the conversation.
  */
case class IdentifierChange(
    kind: IdentifierChangeKind,
    credential: Either[Email, Phone],
    stage: IdentifierChangeStage,
)

object IdentifierChange:
  given JsonCodec[IdentifierChange] =
    JsonCodec[Stored].transform(
      stored =>
        IdentifierChange(
          stored.kind,
          if stored.kind == IdentifierChangeKind.Phone then Right(Phone(stored.value)) else Left(Email(stored.value)),
          stored.stage,
        ),
      change => Stored(change.kind, change.credential.merge, change.stage),
    )

  private case class Stored(kind: IdentifierChangeKind, value: String, stage: IdentifierChangeStage) derives JsonCodec

enum IdentifierChangeStage derives CanEqual:
  case Pending, VerifyNew

object IdentifierChangeStage:
  given JsonCodec[IdentifierChangeStage] = JsonCodec.string.transformOrFail(
    {
      case "pending" => Right(Pending)
      case "verify_new" => Right(VerifyNew)
      case other => Left(s"unknown identifier change stage: $other")
    },
    {
      case Pending => "pending"
      case VerifyNew => "verify_new"
    },
  )

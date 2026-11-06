package versola.central.configuration.edges

import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.schema.*

type EdgeGroupId = EdgeGroupId.Type

object EdgeGroupId:
  opaque type Type <: String = String

  inline def apply(string: String): EdgeGroupId = string

  private val validPattern = "^[a-z][a-z0-9-]*$".r
  private val invalidMessage = "Edge group ID must start with a lowercase letter and contain only lowercase letters, numbers and hyphens"

  def from(string: String): Either[String, EdgeGroupId] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[EdgeGroupId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[EdgeGroupId] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[EdgeGroupId] = JsonEncoder.string.contramap(identity[String])

/** Who runs the edges of a group, which decides what central lets them do.
  *
  * Set when the group is created and never changed, by an operator through the admin API: an
  * edge cannot move itself between kinds, and a partner cannot make its own group `FirstParty`.
  *   - `FirstParty`: the operator's own edges. They read their configuration from central.
  *   - `Partner`: a partner's edges. They get a certificate that names their group, which is what
  *     authenticates the partner's clients to auth, and nothing from central's configuration.
  */
enum EdgeGroupKind(val code: String):
  case FirstParty extends EdgeGroupKind("first_party")
  case Partner extends EdgeGroupKind("partner")

object EdgeGroupKind:
  def from(code: String): Either[String, EdgeGroupKind] =
    values.find(_.code == code).toRight(s"unknown edge group kind '$code', expected one of ${values.map(_.code).mkString(", ")}")

  given Schema[EdgeGroupKind] = Schema.primitive[String].transformOrFail(from, kind => Right(kind.code))
  given JsonDecoder[EdgeGroupKind] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[EdgeGroupKind] = JsonEncoder.string.contramap(_.code)

case class EdgeGroupRecord(
    id: EdgeGroupId,
    kind: EdgeGroupKind,
) derives Schema, JsonCodec

object EdgeGroup:
  /** The group every edge and every internal client belonged to before groups existed. Created
    * by the migration that introduced them. */
  val DefaultId: EdgeGroupId = EdgeGroupId("default")

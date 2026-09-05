package versola.central.configuration.edges

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type EdgeId = EdgeId.Type

object EdgeId:
  opaque type Type <: String = String

  inline def apply(string: String): EdgeId = string

  private val validPattern = "^[a-z][a-z0-9-]*$".r
  private val invalidMessage = "Edge ID must start with a lowercase letter and contain only lowercase letters, numbers and hyphens"

  def from(string: String): Either[String, EdgeId] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[EdgeId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[EdgeId] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[EdgeId] = JsonEncoder.string.contramap(identity[String])

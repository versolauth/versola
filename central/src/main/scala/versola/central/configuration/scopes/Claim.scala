package versola.central.configuration.scopes

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type Claim = Claim.Type

object Claim:
  opaque type Type <: String = String

  inline def apply(string: String): Claim = string

  private val validPattern = "^[a-z][a-z0-9_]*$".r
  private val invalidMessage = "Claim must start with a lowercase letter and contain only lowercase letters, numbers and underscores"

  def from(string: String): Either[String, Claim] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[Claim] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[Claim] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Claim] = JsonDecoder.string.mapOrFail(from)


package versola.central.configuration.scopes

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type Claim = Claim.Type

object Claim:
  opaque type Type <: String = String

  inline def apply(string: String): Claim = string
  inline def from(string: String): Either[String, Claim] = Right(string)
  given Schema[Claim] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[Claim] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Claim] = JsonDecoder.string.map(Claim(_))


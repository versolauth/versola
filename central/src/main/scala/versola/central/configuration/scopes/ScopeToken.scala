package versola.central.configuration.scopes

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type ScopeToken = ScopeToken.Type

object ScopeToken:
  opaque type Type <: String = String

  inline def apply(string: String): ScopeToken = string
  inline def from(string: String): Either[String, ScopeToken] = Right(string)
  given Schema[ScopeToken] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[ScopeToken] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[ScopeToken] = JsonDecoder.string.map(ScopeToken(_))

package versola.central.configuration.scopes

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type ScopeToken = ScopeToken.Type

object ScopeToken:
  opaque type Type <: String = String

  inline def apply(string: String): ScopeToken = string

  private val validPattern = "^[a-z][a-z0-9_]*$".r
  private val invalidMessage = "Scope ID must start with a lowercase letter and contain only lowercase letters, numbers and underscores"

  def from(string: String): Either[String, ScopeToken] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[ScopeToken] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[ScopeToken] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[ScopeToken] = JsonDecoder.string.mapOrFail(from)

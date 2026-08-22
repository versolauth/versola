package versola.central.configuration.clients

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type ClientId = ClientId.Type

object ClientId:
  opaque type Type <: String = String

  inline def apply(string: String): ClientId = string

  private val validPattern = "^[a-z][a-z0-9-]*$".r
  private val invalidMessage = "Client ID must start with a lowercase letter and contain only lowercase letters, numbers and hyphens"

  def from(string: String): Either[String, ClientId] =
    if validPattern.matches(string) then Right(string) else Left(invalidMessage)

  given Schema[ClientId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[ClientId] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[ClientId] = JsonEncoder.string.contramap(identity[String])

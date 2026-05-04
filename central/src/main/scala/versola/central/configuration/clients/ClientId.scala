package versola.central.configuration.clients

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type ClientId = ClientId.Type

object ClientId:
  opaque type Type <: String = String

  inline def apply(string: String): ClientId = string
  inline def from(string: String): Either[String, ClientId] = Right(string)
  given Schema[ClientId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[ClientId] = JsonDecoder.string.map(ClientId(_))
  given JsonEncoder[ClientId] = JsonEncoder.string.contramap(identity[String])

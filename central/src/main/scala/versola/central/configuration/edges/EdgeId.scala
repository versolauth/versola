package versola.central.configuration.edges

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type EdgeId = EdgeId.Type

object EdgeId:
  opaque type Type <: String = String

  inline def apply(string: String): EdgeId = string
  inline def from(string: String): Either[String, EdgeId] = Right(string)
  given Schema[EdgeId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonDecoder[EdgeId] = JsonDecoder.string.map(EdgeId(_))
  given JsonEncoder[EdgeId] = JsonEncoder.string.contramap(identity[String])

package versola.central.configuration.resources

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type ResourceId = ResourceId.Type

object ResourceId:
  opaque type Type <: String = String

  inline def apply(value: String): ResourceId = value
  inline def from(value: String): Either[String, ResourceId] = Right(value)

  given Schema[ResourceId] = Schema.primitive[String].transformOrFail(from, Right(_))
  given JsonEncoder[ResourceId] = JsonEncoder.string
  given JsonDecoder[ResourceId] = JsonDecoder.string
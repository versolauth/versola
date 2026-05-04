package versola.central.configuration.resources

import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

type ResourceId = ResourceId.Type

object ResourceId:
  opaque type Type <: Long = Long

  inline def apply(value: Long): ResourceId = value
  inline def from(value: Long): Either[String, ResourceId] = Right(value)

  given Schema[ResourceId] = Schema.primitive[Long].transformOrFail(from, Right(_))
  given JsonEncoder[ResourceId] = JsonEncoder.long.contramap(identity)
  given JsonDecoder[ResourceId] = JsonDecoder.long.map(ResourceId(_))
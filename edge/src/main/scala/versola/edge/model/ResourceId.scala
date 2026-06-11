package versola.edge.model

import zio.json.{JsonDecoder, JsonEncoder}

type ResourceId = ResourceId.Type

object ResourceId:
  opaque type Type <: Long = Long

  inline def apply(value: Long): ResourceId = value

  given JsonEncoder[ResourceId] = JsonEncoder.long.contramap(identity)
  given JsonDecoder[ResourceId] = JsonDecoder.long.map(ResourceId(_))

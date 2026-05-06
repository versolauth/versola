package versola.central.configuration.resources

import versola.util.{LongNewType, UUIDv7}
import zio.json.{JsonDecoder, JsonEncoder}

type ResourceEndpointId = ResourceEndpointId.Type

object ResourceEndpointId extends UUIDv7:
  given JsonEncoder[ResourceEndpointId] = JsonEncoder.uuid.contramap(identity)
  given JsonDecoder[ResourceEndpointId] = JsonDecoder.uuid.map(ResourceEndpointId(_))
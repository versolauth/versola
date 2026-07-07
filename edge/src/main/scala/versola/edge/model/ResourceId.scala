package versola.edge.model

import versola.util.StringNewType
import zio.json.JsonCodec

type ResourceId = ResourceId.Type

object ResourceId extends StringNewType:
  given JsonCodec[ResourceId] = JsonCodec.string.transform(ResourceId(_), identity[String])

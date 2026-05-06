package versola.edge.model

import versola.util.StringNewType
import zio.json.JsonCodec

type EdgeId = EdgeId.Type

object EdgeId extends StringNewType:
  given JsonCodec[EdgeId] = JsonCodec.string.transform(EdgeId(_), identity[String])

package versola.edge.model

import versola.util.StringNewType
import zio.json.JsonCodec

type Code = Code.Type

object Code extends StringNewType:
  given JsonCodec[Code] = JsonCodec.string.transform(Code(_), identity[String])

package versola.edge.model

import versola.util.StringNewType
import zio.json.JsonCodec

type PresetId = PresetId.Type

object PresetId extends StringNewType:
  given JsonCodec[PresetId] = JsonCodec.string.transform(PresetId(_), identity[String])

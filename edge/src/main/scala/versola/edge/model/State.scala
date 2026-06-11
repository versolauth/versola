package versola.edge.model

import versola.util.StringNewType
import zio.json.JsonCodec

type State = State.Type

object State extends StringNewType.Base64Url:
  given JsonCodec[State] = JsonCodec.string.transform(State(_), identity[String])

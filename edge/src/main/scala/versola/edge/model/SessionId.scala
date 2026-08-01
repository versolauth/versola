package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

/** The `sid` claim of the SSO session the edge session was derived from. */
type SessionId = SessionId.Type

object SessionId extends StringNewType:
  given JsonDecoder[SessionId] = JsonDecoder.string.map(SessionId(_))
  given JsonEncoder[SessionId] = JsonEncoder.string.contramap(identity[String])

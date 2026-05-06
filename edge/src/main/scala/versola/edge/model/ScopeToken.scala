package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

type ScopeToken = ScopeToken.Type

object ScopeToken extends StringNewType:
  given JsonDecoder[ScopeToken] = JsonDecoder.string.map(ScopeToken(_))
  given JsonEncoder[ScopeToken] = JsonEncoder.string.contramap(identity[String])
  val OfflineAccess = ScopeToken("offline_access")
  val OpenId = ScopeToken("openid")
  def parseTokens(s: String): Set[ScopeToken] =
    s.split(" ").map(ScopeToken(_)).toSet

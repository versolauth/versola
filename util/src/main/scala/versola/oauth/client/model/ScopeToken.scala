package versola.oauth.client.model

import versola.util.StringNewType

type ScopeToken = ScopeToken.Type

object ScopeToken extends StringNewType:
  val OfflineAccess = ScopeToken("offline_access")
  val OpenId = ScopeToken("openid")
  def parseTokens(s: String): Set[ScopeToken] =
    s.split(" ").map(ScopeToken(_)).toSet

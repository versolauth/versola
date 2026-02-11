package versola.oauth.client.model

import versola.util.StringNewType

type ScopeToken = ScopeToken.Type

object ScopeToken extends StringNewType:
  val OfflineAccess = ScopeToken("offline_access")

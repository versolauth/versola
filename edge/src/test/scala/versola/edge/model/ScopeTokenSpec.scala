package versola.edge.model

import zio.test.*

object ScopeTokenSpec extends ZIOSpecDefault:
  def spec = suite("ScopeToken")(
    test("parseTokens splits a space-separated scope string") {
      assertTrue(
        ScopeToken.parseTokens("openid offline_access custom:scope") ==
          Set(ScopeToken.OpenId, ScopeToken.OfflineAccess, ScopeToken("custom:scope")),
      )
    },
    test("parseTokens on a single token returns a singleton set") {
      assertTrue(ScopeToken.parseTokens("openid") == Set(ScopeToken.OpenId))
    },
  )

package versola.oauth.model

import zio.test.*

object GrantTypeSpec extends ZIOSpecDefault:
  def spec = suite("GrantType")(
    test("from parses each known grant type") {
      assertTrue(
        GrantType.from("authorization_code") == Right(GrantType.AuthorizationCode),
        GrantType.from("client_credentials") == Right(GrantType.ClientCredentials),
        GrantType.from("refresh_token") == Right(GrantType.RefreshToken),
      )
    },
    test("from rejects an unknown grant type") {
      assertTrue(GrantType.from("implicit") == Left("Unsupported grant type: implicit"))
    },
  )

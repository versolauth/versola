package versola.central.users

import versola.central.TestCentralConfig
import versola.util.JWT
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object AuthTokenServiceSpec extends ZIOSpecDefault:

  private case class TokenClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey = TestCentralConfig.config.secretKey

  def spec = suite("AuthTokenService")(
    test("getToken produces a JWT decodable with the shared secret") {
      for
        service <- ZIO.service[AuthTokenService]
        token   <- service.getToken
        claims  <- JWT.deserialize[TokenClaims](token, secretKey, JWT.Type.JWT)
          .mapError(e => new RuntimeException(e.toString))
      yield assertTrue(
        claims.iss == "central",
        claims.sub == "central",
        claims.aud == List("auth"),
      )
    },
  ).provide(AuthTokenService.live, ZLayer.succeed(TestCentralConfig.config), Scope.default)
    @@ TestAspect.silentLogging

package versola.central.users

import com.nimbusds.jwt.SignedJWT
import versola.central.configuration.topology.CellId
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.JWT
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** The token central presents to an `auth` (#1050): RS256 under the service key, made for the one `auth` it names. */
object AuthTokenServiceSpec extends ZIOSpecDefault:

  private case class TokenClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val servicePublicKeys =
    JWT.PublicKeys.fromJson(Json.Obj("keys" -> Json.Arr(TestCentralConfig.serviceKeyPair.toPublicJwk)))

  private val layer = ZLayer.succeed(TestCentralConfig.config) >>> AuthTokenService.live

  private def claimsOf(token: String) =
    JWT.deserialize[TokenClaims](token, servicePublicKeys, JWT.Type.JWT).mapError(error => RuntimeException(error.toString))

  def spec = suite("AuthTokenService")(
    test("a token is RS256 under the service key, with its kid, and is made for the audience asked") {
      for
        service <- ZIO.service[AuthTokenService]
        token <- service.getToken("kz-1")
        claims <- claimsOf(token)
        header = SignedJWT.parse(token).getHeader
      yield assertTrue(
        claims.iss == "central",
        claims.sub == "central",
        claims.aud == List("kz-1"),
        header.getAlgorithm.getName == "RS256",
        header.getKeyID == TestCentralConfig.serviceKeyPair.keyId,
      )
    },
    test("a token for one audience does not verify as a token for another: each audience has a token of its own") {
      for
        service <- ZIO.service[AuthTokenService]
        kz <- service.getToken("kz-1")
        eu <- service.getToken("eu-1")
        kzClaims <- claimsOf(kz)
        euClaims <- claimsOf(eu)
      yield assertTrue(kz != eu, kzClaims.aud == List("kz-1"), euClaims.aud == List("eu-1"))
    },
    test("one token is kept for an audience and given again until it is nearly out") {
      for
        service <- ZIO.service[AuthTokenService]
        first <- service.getToken("kz-1")
        second <- service.getToken("kz-1")
        _ <- TestClock.adjust(8.minutes)
        stillKept <- service.getToken("kz-1")
        _ <- TestClock.adjust(2.minutes)
        remade <- service.getToken("kz-1")
      yield assertTrue(first == second, first == stillKept, remade != first)
    },
    test("a refreshed token replaces the one kept") {
      for
        service <- ZIO.service[AuthTokenService]
        old <- service.getToken("kz-1")
        fresh <- service.refreshToken("kz-1")
        kept <- service.getToken("kz-1")
      yield assertTrue(fresh != old, kept == fresh)
    },
    test("a token does not verify with a key that is not the service key, and a token of an auth is not one of central's") {
      for
        service <- ZIO.service[AuthTokenService]
        token <- service.getToken("kz-1")
        wrongKey <- JWT.deserialize[TokenClaims](
          token,
          JWT.PublicKeys.fromJson(Json.Obj("keys" -> Json.Arr(TestCentralConfig.foreignKeyPair.toPublicJwk))),
          JWT.Type.JWT,
        ).either
      yield assertTrue(wrongKey.isLeft)
    },
    test("the audience of the configured auth is its cell id, and `auth` when it has none") {
      val named = TestCentralConfig.config.copy(auth = TestCentralConfig.authConfig.copy(cellId = Some(CellId("kz-1"))))
      assertTrue(
        AuthTokenService.audienceOfConfigured(TestCentralConfig.config) == "auth",
        AuthTokenService.audienceOfConfigured(named) == "kz-1",
      )
    },
  ).provide(layer) @@ TestAspect.silentLogging

package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.util.{Base64, ConfigSnapshot, CoreConfig, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.silentLogging

import java.nio.charset.StandardCharsets

object CentralSyncTokenServiceSpec extends ZIOSpecDefault:

  private case class SignedClaims(iss: String, sub: String, aud: List[String], jti: String, iat: Long, exp: Long) derives JsonDecoder

  private val configLayer = ZLayer.succeed(TestEnvConfig.coreConfig)

  private val cellConfigLayer = ZLayer.succeed(TestEnvConfig.cellCoreConfig)

  private val authKeys = TestEnvConfig.publicKeysOf(TestEnvConfig.authKeyPair)

  private val dependencies = (config: ULayer[CoreConfig]) =>
    ZLayer.make[CentralSyncTokenService](
      config,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      Client.default,
      Scope.default,
    )

  /** The header of a JWT, as written. */
  private def headerOf(token: String): zio.json.ast.Json.Obj =
    String(Base64.urlDecode(token.takeWhile(_ != '.')), StandardCharsets.UTF_8).fromJson[zio.json.ast.Json.Obj].toOption.get

  def spec = suite("CentralSyncTokenService")(
    test("getToken returns an RS256 JWT signed with the configured key, under the configured key id, for central") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
        claims <- JWT.deserialize[SignedClaims](token, authKeys, JWT.Type.JWT)
        header = headerOf(token)
      yield assertTrue(
        header.get("alg").flatMap(_.asString).contains("RS256"),
        header.get("kid").flatMap(_.asString).contains(TestEnvConfig.authKeyPair.keyId),
        header.get("typ").flatMap(_.asString).contains("JWT"),
        claims.iss == "auth",
        claims.sub == "auth",
        claims.aud == List("central"),
        claims.jti.nonEmpty,
        claims.exp - claims.iat == 600,
      )
    }.provide(dependencies(configLayer)),
    test("getToken is not verifiable with any other key, central's included") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
        byCentral <- JWT.deserialize[SignedClaims](token, TestEnvConfig.publicKeysOf(TestEnvConfig.centralKeyPair), JWT.Type.JWT).either
        // the same key id, another key: what a cell claiming to be this one would present
        byImpostor <- JWT.deserialize[SignedClaims](
          token,
          TestEnvConfig.publicKeysOf(
            TestEnvConfig.anotherCellKeyPair.copy(keyId = TestEnvConfig.authKeyPair.keyId),
          ),
          JWT.Type.JWT,
        ).either
      yield assertTrue(byCentral.isLeft, byImpostor.isLeft)
    }.provide(dependencies(configLayer)),
    test("a cell's token names the cell in the cell_id header and as its subject") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
        claims <- JWT.deserialize[SignedClaims](token, authKeys, JWT.Type.JWT)
        header = headerOf(token)
      yield assertTrue(
        header.get("cell_id").flatMap(_.asString).contains(TestEnvConfig.cellId: String),
        header.get("kid").flatMap(_.asString).contains(TestEnvConfig.authKeyPair.keyId),
        claims.sub == (TestEnvConfig.cellId: String),
        claims.iss == "auth",
        claims.aud == List("central"),
      )
    }.provide(dependencies(cellConfigLayer)),
    test("the token of an auth with no cell id has no cell_id header") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token <- service.getToken
      yield assertTrue(!headerOf(token).fields.exists(_._1 == "cell_id"))
    }.provide(dependencies(configLayer)),
    test("getToken returns the cached token on repeated calls") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token1 <- service.getToken
        token2 <- service.getToken
      yield assertTrue(token1 == token2)
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      Client.default,
      Scope.default,
    ),
    test("syncRequest forwards the request with a Bearer Authorization header") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { req =>
            seen.set(Some(req)).as(Response.ok)
          }.toRoutes,
        )
        service <- ZIO.service[CentralSyncTokenService]
        _ <- ZIO.scoped(service.syncRequest(Request.get(URL.empty)))
        captured <- seen.get.someOrFail(RuntimeException("No request seen"))
      yield assertTrue(
        captured.header(Header.Authorization) match
          case Some(Header.Authorization.Bearer(_)) => true
          case _ => false,
      )
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      TestClient.layer,
      Scope.default,
    ),
    test("syncRequest retries with a fresh token on 401 response") {
      for
        callCount <- Ref.make(0)
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { _ =>
            callCount.getAndUpdate(_ + 1).map { n =>
              if n == 0 then Response.status(Status.Unauthorized)
              else Response.ok
            }
          }.toRoutes,
        )
        service <- ZIO.service[CentralSyncTokenService]
        response <- ZIO.scoped(service.syncRequest(Request.get(URL.empty)))
        calls <- callCount.get
      yield assertTrue(response.status == Status.Ok, calls == 2)
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
      TestClient.layer,
      Scope.default,
    ),
  ) @@ silentLogging

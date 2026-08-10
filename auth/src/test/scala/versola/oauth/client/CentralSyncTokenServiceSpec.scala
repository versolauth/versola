package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.util.{CoreConfig, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.silentLogging

import javax.crypto.spec.SecretKeySpec

object CentralSyncTokenServiceSpec extends ZIOSpecDefault:

  private case class SignedClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey   = SecretKeySpec(Array.fill(32)(5.toByte), "AES")
  private val configLayer = ZLayer.succeed(
    TestEnvConfig.coreConfig.copy(central = CoreConfig.CentralSyncConfig(URL.empty, secretKey))
  )

  def spec = suite("CentralSyncTokenService")(
    test("getToken returns a JWT signed with the configured secret key") {
      for
        service <- ZIO.service[CentralSyncTokenService]
        token   <- service.getToken
        claims  <- JWT.deserialize[SignedClaims](token, secretKey, JWT.Type.JWT)
      yield assertTrue(
        claims.iss == "auth",
        claims.sub == "auth",
        claims.aud.contains("central"),
      )
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      Client.default,
      Scope.default,
    ),

    test("getToken returns the cached token on repeated calls") {
      for
        service  <- ZIO.service[CentralSyncTokenService]
        token1   <- service.getToken
        token2   <- service.getToken
      yield assertTrue(token1 == token2)
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      Client.default,
      Scope.default,
    ),

    test("syncRequest forwards the request with a Bearer Authorization header") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { req =>
            seen.set(Some(req)).as(Response.ok)
          }.toRoutes
        )
        service  <- ZIO.service[CentralSyncTokenService]
        _        <- ZIO.scoped(service.syncRequest(Request.get(URL.empty)))
        captured <- seen.get.someOrFail(RuntimeException("No request seen"))
      yield assertTrue(
        captured.header(Header.Authorization) match
          case Some(Header.Authorization.Bearer(_)) => true
          case _                                    => false
      )
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
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
          }.toRoutes
        )
        service   <- ZIO.service[CentralSyncTokenService]
        response  <- ZIO.scoped(service.syncRequest(Request.get(URL.empty)))
        calls     <- callCount.get
      yield assertTrue(response.status == Status.Ok, calls == 2)
    }.provide(
      configLayer,
      CentralSyncTokenService.live,
      TestClient.layer,
      Scope.default,
    ),
  ) @@ silentLogging

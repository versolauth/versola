package versola.oauth.jwks

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.util.{CoreConfig, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object JwksSyncClientSpec extends ZIOSpecDefault:
  private case class SignedClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey   = SecretKeySpec(Array.fill(32)(12.toByte), "AES")
  private val configLayer = ZLayer.succeed(
    TestEnvConfig.coreConfig.copy(central = CoreConfig.CentralSyncConfig(URL.empty, secretKey))
  )

  private val tokenLayer: ZLayer[Client, Throwable, CentralSyncTokenService] = ZLayer.fromZIO(
    for
      client <- ZIO.service[Client]
      token  <- JWT.serialize(
        JWT.Claims("auth", "internal-auth", List("central"), Json.Obj()),
        10.minutes,
        JWT.Signature.Symmetric(secretKey),
      )
    yield new CentralSyncTokenService:
      override def getToken: UIO[String] = ZIO.succeed(token)
      override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] =
        client.request(request.addHeader(Header.Authorization.Bearer(token)))
  )

  def spec = suite("JwksSyncClient")(
    test("fetches JWKS from central /configuration/jwks/sync with bearer token") {
      for
        seen   <- Ref.make(Option.empty[Request])
        _      <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(TestEnvConfig.jwksJson.toJson))
          }.toRoutes
        )
        client  <- ZIO.service[JwksSyncClient]
        result  <- client.getAll
        request <- seen.get.someOrFail(new RuntimeException("No request captured"))
        token   <- ZIO
          .fromOption(request.header(Header.Authorization).collect { case Header.Authorization.Bearer(v) => v.stringValue })
          .orElseFail(new RuntimeException("Missing bearer token"))
        claims  <- JWT.deserialize[SignedClaims](token, secretKey, JWT.Type.JWT).mapError(e => new RuntimeException(e.toString))
      yield assertTrue(
        request.method == Method.GET,
        request.url.encode.contains("configuration/jwks/sync"),
        claims.iss == "auth",
        claims.sub == "internal-auth",
        claims.aud == List("central"),
        result.keys.size() == 1,
      )
    },
  ).provideShared(TestClient.layer, configLayer, tokenLayer, JwksSyncClient.live) @@ TestAspect.silentLogging

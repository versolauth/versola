package versola.edge

import versola.util.JWT
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object JwksSyncClientSpec extends ZIOSpecDefault:
  private val syncToken = "sync-token"

  private val keyPair =
    val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private val rsaJwk =
    com.nimbusds.jose.jwk.RSAKey.Builder(keyPair.getPublic.asInstanceOf[java.security.interfaces.RSAPublicKey])
      .keyID("central-key")
      .build()

  private val jwksJson = zio.json.ast.Json.Obj(
    "keys" -> zio.json.ast.Json.Arr(
      zio.json.ast.Json.Obj(
        "kid" -> zio.json.ast.Json.Str("central-key"),
        "kty" -> zio.json.ast.Json.Str("RSA"),
        "n" -> zio.json.ast.Json.Str(rsaJwk.getModulus.toString),
        "e" -> zio.json.ast.Json.Str(rsaJwk.getPublicExponent.toString),
      ),
    ),
  )

  private val config = EdgeConfig(
    versola.edge.model.EdgeId("edge-1"),
    "edge-key",
    keyPair.getPrivate.nn,
    EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(versola.util.Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(versola.util.Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val centralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed(syncToken)

  def spec = suite("JwksSyncClient")(
    test("fetches JWKS from central with a bearer token") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(jwksJson.toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = JwksSyncClient.Impl(client, config, centralSyncTokenService)
        keys <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/jwks/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        keys.active.id == "central-key",
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging

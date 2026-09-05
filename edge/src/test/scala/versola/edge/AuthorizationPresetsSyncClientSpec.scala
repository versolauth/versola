package versola.edge

import versola.edge.model.{AuthorizationPreset, ClientId, PresetId}
import versola.util.RedirectUri
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object AuthorizationPresetsSyncClientSpec extends ZIOSpecDefault:
  private val syncToken = "sync-token"

  private val keyPair =
    val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

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

  private val preset = AuthorizationPreset(
    id = PresetId("preset-1"),
    clientId = ClientId("web-app"),
    description = "Default preset",
    redirectUri = RedirectUri("https://app.example/complete"),
    postLoginRedirectUri = RedirectUri("https://app.example/home"),
    postLogoutRedirectUri = None,
    scope = Set("openid"),
    responseType = "code",
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  private case class ResponseMirror(presets: Vector[AuthorizationPreset]) derives JsonCodec

  def spec = suite("AuthorizationPresetsSyncClient")(
    test("maps presets by id") {
      val body = ResponseMirror(Vector(preset)).toJson
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(body))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = AuthorizationPresetsSyncClient.Impl(client, config, centralSyncTokenService)
        presets <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/auth-request-presets/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        presets == Map(PresetId("preset-1") -> preset),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging

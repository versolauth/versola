package versola.edge

import versola.edge.model.{AuthorizationPreset, ClientId, OAuthClient, PresetId}
import versola.util.{RedirectUri, ReloadingCache, Secret}
import zio.*
import zio.test.*

object OAuthClientServiceSpec extends ZIOSpecDefault:
  private val clientId = ClientId("web-app")
  private val otherClientId = ClientId("mobile-app")
  private val presetId = PresetId("preset-default")
  private val otherPresetId = PresetId("preset-mobile")

  private val client = OAuthClient(id = clientId, secret = Secret(Array.fill(48)(1.toByte)), permissions = Set.empty)
  private val otherClient = OAuthClient(id = otherClientId, secret = Secret(Array.fill(48)(2.toByte)), permissions = Set.empty)

  private val preset = AuthorizationPreset(
    id = presetId,
    clientId = clientId,
    description = "Default web login",
    redirectUri = RedirectUri("https://app.example/callback"),
    postLoginRedirectUri = RedirectUri("https://app.example/home"),
    scope = Set("openid", "profile"),
    responseType = "code",
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = Some("app.example"),
    cookiePath = Some("/"),
  )

  private def env(
      presets: Map[PresetId, AuthorizationPreset] = Map.empty,
      clients: Map[ClientId, OAuthClient] = Map.empty,
  ): UIO[OAuthClientService] =
    for
      presetCache <- Ref.make(presets).map(ReloadingCache(_))
      clientCache <- Ref.make(clients).map(ReloadingCache(_))
    yield OAuthClientService.Impl(presetCache, clientCache)

  def spec = suite("edge.OAuthClientService")(
    test("findPreset returns cached preset when id is known") {
      for
        service <- env(presets = Map(presetId -> preset))
        result  <- service.findPreset(presetId)
      yield assertTrue(result.contains(preset))
    },
    test("findPreset returns None when id is unknown") {
      for
        service <- env(presets = Map(presetId -> preset))
        result  <- service.findPreset(otherPresetId)
      yield assertTrue(result.isEmpty)
    },
    test("findClient returns cached client when id is known") {
      for
        service <- env(clients = Map(clientId -> client, otherClientId -> otherClient))
        result  <- service.findClient(otherClientId)
      yield assertTrue(result.contains(otherClient))
    },
    test("findClient returns None when id is unknown") {
      for
        service <- env(clients = Map(clientId -> client))
        result  <- service.findClient(otherClientId)
      yield assertTrue(result.isEmpty)
    },
  )

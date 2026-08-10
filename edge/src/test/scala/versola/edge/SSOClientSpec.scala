package versola.edge

import versola.edge.model.*
import versola.util.{Base64, RedirectUri, Secret}
import zio.*
import zio.http.*
import zio.test.*

import java.security.KeyPairGenerator

object SSOClientSpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
  )

  private val basePreset = AuthorizationPreset(
    id = PresetId("test"),
    clientId = ClientId("web-app"),
    description = "test",
    redirectUri = RedirectUri("https://app.example/callback"),
    postLoginRedirectUri = RedirectUri("https://app.example/home"),
    postLogoutRedirectUri = None,
    scope = Set("openid"),
    responseType = "code",
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  private val state = State.fromBytes(Array.fill(16)(1.toByte))

  private def queryParam(url: URL, key: String): Option[Chunk[String]] =
    url.queryParams.map.get(key)

  def spec = suite("SSOClient.authorizeUri")(
    test("overrideParams replace a same-named key in customParameters") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        preset = basePreset.copy(customParameters = Map("prompt" -> List("none")))
        url <- sso.authorizeUri(preset, "challenge", state, Map("prompt" -> "login"))
      yield assertTrue(
        queryParam(url, "prompt") == Some(Chunk("login")),
      )
    },
    test("overrideParams replace ui_locales from preset.uiLocales") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        preset = basePreset.copy(uiLocales = Some(List("en", "fr")))
        url <- sso.authorizeUri(preset, "challenge", state, Map("ui_locales" -> "de"))
      yield assertTrue(
        queryParam(url, "ui_locales") == Some(Chunk("de")),
      )
    },
    test("overrideParams are added when not present in preset") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        url <- sso.authorizeUri(basePreset, "challenge", state, Map("acr_values" -> "mfa"))
      yield assertTrue(
        queryParam(url, "acr_values") == Some(Chunk("mfa")),
      )
    },
    test("empty overrideParams preserves customParameters and uiLocales") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config)
        preset = basePreset.copy(
          uiLocales = Some(List("en")),
          customParameters = Map("prompt" -> List("none")),
        )
        url <- sso.authorizeUri(preset, "challenge", state, Map.empty)
      yield assertTrue(
        queryParam(url, "ui_locales") == Some(Chunk("en")),
        queryParam(url, "prompt") == Some(Chunk("none")),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

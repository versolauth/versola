package versola.central.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{DatabaseSpecBase, RedirectUri}
import zio.prelude.EqualOps
import zio.test.*

trait AuthorizationPresetRepositorySpec extends DatabaseSpecBase[AuthorizationPresetRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenantId = TenantId("tenant-a")
  val clientId = ClientId("client-1")
  val clientId2 = ClientId("client-2")

  val preset1 = AuthorizationPreset(
    id = PresetId("web-login"),
    clientId = clientId,
    description = "Web Login",
    redirectUri = RedirectUri("https://example.com/callback"),
    postLoginRedirectUri = RedirectUri("https://example.com/dashboard"),
    scope = Set(ScopeToken("openid"), ScopeToken("profile")),
    responseType = ResponseType.Code,
    uiLocales = Some(List("en", "fr")),
    customParameters = Map.empty,
    cookieDomain = Some("example.com"),
    cookiePath = Some("/"),
  )

  val preset2 = AuthorizationPreset(
    id = PresetId("mobile-login"),
    clientId = clientId,
    description = "Mobile Login",
    redirectUri = RedirectUri("https://example.com/mobile"),
    postLoginRedirectUri = RedirectUri("https://example.com/mobile/home"),
    scope = Set(ScopeToken("openid"), ScopeToken("email")),
    responseType = ResponseType.CodeIdToken,
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  val preset3 = AuthorizationPreset(
    id = PresetId("other-client-preset"),
    clientId = clientId2,
    description = "Other Client Preset",
    redirectUri = RedirectUri("https://other.com/callback"),
    postLoginRedirectUri = RedirectUri("https://other.com/home"),
    scope = Set(ScopeToken("openid")),
    responseType = ResponseType.Code,
    uiLocales = Some(List("de")),
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  override def testCases(env: AuthorizationPresetRepositorySpec.Env) =
    List(
      test("find returns preset by id") {
        for
          notFound <- env.repository.find(preset1.id)
          _ <- env.repository.replace(clientId, List(preset1))
          found <- env.repository.find(preset1.id)
        yield assertTrue(
          notFound.isEmpty,
          found === Some(preset1),
        )
      },
      test("getAll returns all presets ordered correctly") {
        for
          _ <- env.repository.replace(clientId, List(preset1, preset2))
          _ <- env.repository.replace(clientId2, List(preset3))
          all <- env.repository.getAll
        yield assertTrue(all === Vector(preset2, preset1, preset3))
      },
      test("replace deletes old presets and inserts new ones") {
        val newPreset1 = preset1.copy(description = "New Web Login")
        val newPreset2 = preset2.copy(description = "New Mobile Login")
        for
          _ <- env.repository.replace(clientId, List(preset1, preset2))
          _ <- env.repository.replace(clientId2, List(preset3))
          _ <- env.repository.replace(clientId, List(newPreset1, newPreset2))
          allPresets <- env.repository.getAll
        yield assertTrue(
          allPresets === Vector(newPreset2, newPreset1, preset3),
        )
      },
      test("replace with empty list deletes all presets for client") {
        for
          _ <- env.repository.replace(clientId, List(preset1, preset2))
          _ <- env.repository.replace(clientId2, List(preset3))
          _ <- env.repository.replace(clientId, List.empty)
          allPresets <- env.repository.getAll
        yield assertTrue(
          allPresets === Vector(preset3),
        )
      },
    )

object AuthorizationPresetRepositorySpec:
  case class Env(repository: AuthorizationPresetRepository)

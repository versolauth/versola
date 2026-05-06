package versola.central.configuration.clients

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.{AuthorizationPresetInput, SaveAuthorizationPresetsRequest}
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{RedirectUri, ReloadingCache}
import zio.*
import zio.durationInt
import zio.test.*

object AuthorizationPresetServiceSpec extends ZIOSpecDefault, ZIOStubs:

  class Env(initial: Vector[AuthorizationPreset] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val presetRepo = stub[AuthorizationPresetRepository]
    val clientService = stub[OAuthClientService]
    val service = AuthorizationPresetService.Impl(cache, presetRepo, clientService)

  private val edgeId = EdgeId("edge-1")
  private val tenantId = TenantId("tenant-a")
  private val clientId = ClientId("web-app")
  
  private val client = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = "Web App",
    redirectUris = Set(RedirectUri("https://example.com/callback")),
    scope = Set(ScopeToken("openid"), ScopeToken("profile")),
    externalAudience = Nil,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set.empty,
  )
  
  private val validRequest = SaveAuthorizationPresetsRequest(
    clientId = clientId,
    presets = List(
      AuthorizationPresetInput(
        id = PresetId("web-login"),
        description = "Web Login",
        redirectUri = RedirectUri("https://example.com/callback"),
        postLoginRedirectUri = RedirectUri("https://example.com/dashboard"),
        scope = Set(ScopeToken("openid")),
        responseType = ResponseType.Code,
        uiLocales = None,
        customParameters = Map.empty,
        cookieDomain = None,
        cookiePath = None,
      ),
    ),
  )

  private val preset1 = AuthorizationPreset(
    id = PresetId("web-login"),
    clientId = clientId,
    description = "Web Login",
    redirectUri = RedirectUri("https://example.com/callback"),
    postLoginRedirectUri = RedirectUri("https://example.com/dashboard"),
    scope = Set(ScopeToken("openid")),
    responseType = ResponseType.Code,
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  override def spec = suite("AuthorizationPresetService")(
    test("save presets successfully when client exists and data is valid") {
      val env = Env()
      for
        _ <- env.clientService.getAllClients.succeedsWith(Vector(client))
        _ <- env.presetRepo.replace.succeedsWith(())

        result <- env.service.savePresets(validRequest)
      yield assertTrue(result.isRight)
    },
    test("return error when client not found") {
      val env = Env()
      for
        _ <- env.clientService.getAllClients.succeedsWith(Vector.empty)

        result <- env.service.savePresets(validRequest)
      yield assertTrue(
        result.isLeft,
        result.swap.exists(_ == PresetValidationError.ClientNotFound),
      )
    },
    test("return error when redirect URI not in client's allowed URIs") {
      val invalidRequest = validRequest.copy(
        presets = List(
          AuthorizationPresetInput(
            id = PresetId("invalid"),
            description = "Invalid",
            redirectUri = RedirectUri("https://invalid.com/callback"),
            postLoginRedirectUri = RedirectUri("https://invalid.com/dashboard"),
            scope = Set(ScopeToken("openid")),
            responseType = ResponseType.Code,
            uiLocales = None,
            customParameters = Map.empty,
            cookieDomain = None,
            cookiePath = None,
          ),
        ),
      )
      val env = Env()
      for
        _ <- env.clientService.getAllClients.succeedsWith(Vector(client))
        result <- env.service.savePresets(invalidRequest)
      yield assertTrue(
        result.isLeft,
        result.swap.exists(_ == PresetValidationError.InvalidRedirectUri),
      )
    },
    test("return error when scope is not subset of client's scope") {
      val invalidRequest = validRequest.copy(
        presets = List(
          AuthorizationPresetInput(
            id = PresetId("invalid"),
            description = "Invalid",
            redirectUri = RedirectUri("https://example.com/callback"),
            postLoginRedirectUri = RedirectUri("https://example.com/dashboard"),
            scope = Set(ScopeToken("admin")),
            responseType = ResponseType.Code,
            uiLocales = None,
            customParameters = Map.empty,
            cookieDomain = None,
            cookiePath = None,
          ),
        ),
      )
      val env = Env()
      for
        _ <- env.clientService.getAllClients.succeedsWith(Vector(client))
        result <- env.service.savePresets(invalidRequest)
      yield assertTrue(
        result.isLeft,
        result.swap.exists(_ == PresetValidationError.InvalidScope),
      )
    },
    test("get client presets from cache") {
      val env = Env(Vector(preset1))
      for
        result <- env.service.getClientPresets(clientId)
      yield assertTrue(result == Vector(preset1))
    },
    test("getPresetsForSync returns all presets when no edge filter") {
      val env = Env(Vector(preset1))
      for
        result <- env.service.getPresetsForSync(None)
      yield assertTrue(result == Vector(preset1))
    },
    test("getPresetsForSync filters by edge id via OAuthClientService") {
      val client2 = client.copy(id = ClientId("client-2"), tenantId = TenantId("tenant-b"))
      val preset2 = preset1.copy(id = PresetId("preset2"), clientId = client2.id)
      val env = Env(Vector(preset1, preset2))
      for
        _ <- env.clientService.getClientsForSync.succeedsWith(Vector(client))
        result <- env.service.getPresetsForSync(Some(edgeId))
      yield assertTrue(result == Vector(preset1))
    },
  )

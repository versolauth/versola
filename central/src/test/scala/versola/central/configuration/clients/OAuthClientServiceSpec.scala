package versola.central.configuration.clients

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.central.configuration.{CreateClientRequest, PatchClientRedirectUris, PatchClientScope, PatchPermissions, UpdateClientRequest}
import versola.util.{MAC, RedirectUri, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.prelude.EqualOps
import zio.*
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object OAuthClientServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val clientId = ClientId("web-app")
  private val otherClientId = ClientId("mobile-app")
  private val redirectUri1 = RedirectUri("https://example.com/callback")
  private val redirectUri2 = RedirectUri("https://example.com/mobile")
  private val readScope = ScopeToken("read")
  private val writeScope = ScopeToken("write")
  private val readPermission = Permission("users:read")
  private val writePermission = Permission("users:write")
  private val pepper = TestCentralConfig.config.clientSecretsPepper

  private val cachedClient = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = "Web App",
    redirectUris = Set(redirectUri1),
    scope = Set(readScope),
    externalAudience = List(ClientId("api")),
    secret = Some(Secret(Array.fill(48)(1.toByte))),
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set(readPermission),
    theme = "default",
    authFlow = Some(AuthFlow.default),
    otpTemplateId = "default",
  )

  private val otherTenantClient = OAuthClientRecord(
    id = otherClientId,
    tenantId = otherTenantId,
    clientName = "Mobile App",
    redirectUris = Set(redirectUri2),
    scope = Set(writeScope),
    externalAudience = Nil,
    secret = Some(Secret(Array.fill(48)(2.toByte))),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set(writePermission),
    theme = "default",
    authFlow = Some(AuthFlow.default),
    otpTemplateId = "default",
  )

  private val createRequest = CreateClientRequest(
    tenantId = tenantId,
    id = clientId,
    clientName = "Web App",
    redirectUris = Set(redirectUri1),
    allowedScopes = Set(readScope),
    audience = List(ClientId("api")),
    permissions = Set(readPermission),
    accessTokenTtl = 300,
    refreshTokenTtl = Some(7776000),
    theme = "default",
    authFlow = Some(AuthFlow.default),
    otpTemplateId = "default",
  )

  private val updateRequest = UpdateClientRequest(
    clientId = clientId,
    clientName = Some("Updated Web App"),
    redirectUris = PatchClientRedirectUris(add = Set(redirectUri2), remove = Set(redirectUri1)),
    scope = PatchClientScope(add = Set(writeScope), remove = Set(readScope)),
    permissions = PatchPermissions(add = Set(writePermission), remove = Set(readPermission)),
    accessTokenTtl = Some(900L),
    refreshTokenTtl = None,
    theme = None,
    authFlow = None,
    otpTemplateId = None,
  )

  class Env(initial: Vector[OAuthClientRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[OAuthClientRepository]
    val tenantRepository = stub[TenantRepository]
    val secureRandom = stub[SecureRandom]
    val securityService = stub[SecurityService]
    val config = TestCentralConfig.config
    val service = OAuthClientService.Impl(cache, repository, tenantRepository, secureRandom, securityService, config)

  def spec = suite("OAuthClientService")(
    test("getTenantClients filters cache by tenant") {
      val env = new Env(Vector(cachedClient, otherTenantClient))

      for
        result <- env.service.getTenantClients(tenantId, offset = 0, limit = None)
      yield assertTrue(result === Vector(cachedClient))
    },
    test("getClientsForSync returns all clients when no edge filter") {
      val env = new Env(Vector(cachedClient, otherTenantClient))

      for
        result <- env.service.getClientsForSync(None)
      yield assertTrue(result === Vector(cachedClient, otherTenantClient))
    },
    test("getClientsForSync filters by edge id via tenants") {
      val edgeId = EdgeId("edge-1")
      val env = new Env(Vector(cachedClient, otherTenantClient))

      for
        _ <- env.tenantRepository.getAll.succeedsWith(Vector(
          TenantRecord(tenantId, "Tenant A", Some(edgeId)),
          TenantRecord(otherTenantId, "Tenant B", Some(EdgeId("other-edge"))),
        ))
        result <- env.service.getClientsForSync(Some(edgeId))
      yield assertTrue(result === Vector(cachedClient))
    },
    test("getTenantClients applies pagination after filtering") {
      val env = new Env(Vector(cachedClient, cachedClient.copy(id = ClientId("spa-app"), clientName = "SPA App"), otherTenantClient))
      val secondClient = cachedClient.copy(id = ClientId("spa-app"), clientName = "SPA App")

      for
        result <- env.service.getTenantClients(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result === Vector(secondClient))
    },
    test("registerClient returns generated secret and persists MAC with salt") {
      val env = new Env()
      val secretBytes = Array.fill(32)(11.toByte)
      val saltBytes = Array.fill(16)(13.toByte)
      val macBytes = Array.fill(32)(17.toByte)
      val storedSecret = Secret(macBytes ++ saltBytes)
      val expectedClient = OAuthClientRecord(
        id = clientId,
        tenantId = tenantId,
        clientName = "Web App",
        redirectUris = Set(redirectUri1),
        scope = Set(readScope),
        externalAudience = List(ClientId("api")),
        secret = Some(storedSecret),
        previousSecret = None,
        accessTokenTtl = 300.seconds,
        refreshTokenTtl = 7776000.seconds,
        permissions = Set(readPermission),
        theme = "default",
        authFlow = Some(AuthFlow.default),
        otpTemplateId = "default",
      )

      for
        _ <- env.secureRandom.nextBytes.returnsZIOOnCall:
          case 1 => ZIO.succeed(secretBytes)
          case _ => ZIO.succeed(saltBytes)
        _ <- env.securityService.mac.succeedsWith(MAC(macBytes))
        _ <- env.repository.createClient.succeedsWith(())
        result <- env.service.registerClient(createRequest)
        created = env.repository.createClient.calls.head
        macCall = env.securityService.mac.calls.head
      yield assertTrue(
        result.sameElements(secretBytes),
        macCall._1 === Secret(secretBytes),
        macCall._2.sameElements(saltBytes ++ pepper),
        created === expectedClient,
      )
    },
    test("updateClient maps request to repository call") {
      val env = new Env()

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest)
      yield assertTrue(
        env.repository.updateClient.calls == List(
          (
            clientId,
            Some("Updated Web App"),
            updateRequest.redirectUris,
            updateRequest.scope,
            updateRequest.permissions,
            Some(900.seconds),
            None,
            None,
            None,
            None,
          )
        )
      )
    },
    test("rotateClientSecret returns new secret and stores MAC with salt") {
      val env = new Env()
      val secretBytes = Array.fill(32)(21.toByte)
      val saltBytes = Array.fill(16)(23.toByte)
      val macBytes = Array.fill(32)(27.toByte)
      val storedSecret = Secret(macBytes ++ saltBytes)

      for
        _ <- env.secureRandom.nextBytes.returnsZIOOnCall:
          case 1 => ZIO.succeed(secretBytes)
          case _ => ZIO.succeed(saltBytes)
        _ <- env.securityService.mac.succeedsWith(MAC(macBytes))
        _ <- env.repository.rotateClientSecret.succeedsWith(())
        result <- env.service.rotateClientSecret(clientId)
        rotateCall = env.repository.rotateClientSecret.calls.head
        macCall = env.securityService.mac.calls.head
      yield assertTrue(
        result.sameElements(secretBytes),
        macCall._1 === Secret(secretBytes),
        macCall._2.sameElements(saltBytes ++ pepper),
        rotateCall._1 == clientId,
        rotateCall._2.sameElements(storedSecret),
      )
    },
    test("deletePreviousClientSecret and deleteClient delegate to repository") {
      val env = new Env()

      for
        _ <- env.repository.deletePreviousClientSecret.succeedsWith(())
        _ <- env.repository.deleteClient.succeedsWith(())
        _ <- env.service.deletePreviousClientSecret(clientId)
        _ <- env.service.deleteClient(clientId)
      yield assertTrue(
        env.repository.deletePreviousClientSecret.calls === List(clientId),
        env.repository.deleteClient.calls === List(clientId),
      )
    },
    test("sync removes cached client on delete event") {
      val env = new Env(Vector(cachedClient, otherTenantClient))

      for
        _ <- env.service.sync(SyncEvent.ClientsUpdated(clientId, SyncEvent.Op.DELETE))
        cached <- env.cache.get
      yield assertTrue(cached === Vector(otherTenantClient))
    },
    test("sync upserts fetched client for non-delete event") {
      val env = new Env(Vector(cachedClient, otherTenantClient))
      val updatedClient = cachedClient.copy(clientName = "Updated Web App", permissions = Set(readPermission, writePermission))

      for
        _ <- env.repository.find.succeedsWith(Some(updatedClient))
        _ <- env.service.sync(SyncEvent.ClientsUpdated(clientId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.find.calls === List(clientId),
        cached === Vector(otherTenantClient, updatedClient),  // sorted by ID: mobile-app, web-app
      )
    },
    test("sync removes cached client when record is missing on non-delete event") {
      val env = new Env(Vector(cachedClient, otherTenantClient))

      for
        _ <- env.repository.find.succeedsWith(None)
        _ <- env.service.sync(SyncEvent.ClientsUpdated(clientId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.find.calls === List(clientId),
        cached === Vector(otherTenantClient),
      )
    },
  )
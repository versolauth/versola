package versola.central.configuration.clients

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.roles.{RoleRecord, RoleRepository}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.central.configuration.{
  ConsentFlowDto,
  CreateClientRequest,
  PatchClientRedirectUris,
  PatchClientScope,
  PatchPermissions,
  UpdateClientRequest,
}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.{Patch, RedirectUri, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.URL
import zio.prelude.EqualOps
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

  private val cachedClient = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = Map("en" -> "Web App"),
    redirectUris = Set(redirectUri1),
    scope = Set(readScope),
    secret = Some(Secret(Array.fill(48)(1.toByte))),
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set(readPermission),
    theme = "default",
    authFlow = Some(AuthFlow.default),
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
  )

  private val otherTenantClient = OAuthClientRecord(
    id = otherClientId,
    tenantId = otherTenantId,
    clientName = Map("en" -> "Mobile App"),
    redirectUris = Set(redirectUri2),
    scope = Set(writeScope),
    secret = Some(Secret(Array.fill(48)(2.toByte))),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set(writePermission),
    theme = "default",
    authFlow = Some(AuthFlow.default),
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
  )

  private val createRequest = CreateClientRequest(
    tenantId = tenantId,
    id = clientId,
    clientName = Map("en" -> "Web App"),
    redirectUris = Set(redirectUri1),
    allowedScopes = Set(readScope),
    permissions = Set(readPermission),
    accessTokenTtl = 300,
    refreshTokenTtl = Some(7776000),
    theme = "default",
    authFlow = Some(AuthFlow.default),
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
  )

  private val updateRequest = UpdateClientRequest(
    clientId = clientId,
    clientName = Some(Map("en" -> "Updated Web App")),
    redirectUris = PatchClientRedirectUris(add = Set(redirectUri2), remove = Set(redirectUri1)),
    scope = PatchClientScope(add = Set(writeScope), remove = Set(readScope)),
    permissions = PatchPermissions(add = Set(writePermission), remove = Set(readPermission)),
    accessTokenTtl = Some(900L),
    refreshTokenTtl = None,
    theme = None,
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = None,
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = None,
    backChannelLogoutUri = None,
  )

  class Env(initial: Vector[OAuthClientRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[OAuthClientRepository]
    val tenantRepository = stub[TenantRepository]
    val roleRepository = stub[RoleRepository]
    val secureRandom = stub[SecureRandom]
    val securityService = stub[SecurityService]
    val config = TestCentralConfig.config
    val service = OAuthClientService.Impl(cache, repository, tenantRepository, roleRepository, secureRandom, securityService, config)

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
      val env = new Env(Vector(cachedClient, cachedClient.copy(id = ClientId("spa-app"), clientName = Map("en" -> "SPA App")), otherTenantClient))
      val secondClient = cachedClient.copy(id = ClientId("spa-app"), clientName = Map("en" -> "SPA App"))

      for
        result <- env.service.getTenantClients(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result === Vector(secondClient))
    },
    test("registerClient returns generated secret and persists encrypted secret") {
      val env = new Env()
      val secretBytes = Array.fill(32)(11.toByte)
      val encryptedBytes = Array.fill(48)(17.toByte)
      val storedSecret = Secret(encryptedBytes)
      val consentFlow = ConsentFlowDto(allowPartial = true, rememberDuration = Some(14.days.toSeconds))
      val expectedClient = OAuthClientRecord(
        id = clientId,
        tenantId = tenantId,
        clientName = Map("en" -> "Web App"),
        redirectUris = Set(redirectUri1),
        scope = Set(readScope),
        secret = Some(storedSecret),
        previousSecret = None,
        accessTokenTtl = 300.seconds,
        refreshTokenTtl = 7776000.seconds,
        permissions = Set(readPermission),
        theme = "default",
        authFlow = Some(AuthFlow.default),
        registrationFlow = None,
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = None,
        policyUri = None,
        tosUri = None,
        consentFlow = Some(ConsentFlow(allowPartial = true, rememberDuration = Some(14.days))),
      )

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(secretBytes)
        _ <- env.securityService.encryptAes256.succeedsWith(encryptedBytes)
        _ <- env.repository.createClient.succeedsWith(())
        result <- env.service.registerClient(createRequest.copy(consentFlow = Some(consentFlow)))
        created = env.repository.createClient.calls.head
        encryptCall = env.securityService.encryptAes256.calls.head
      yield assertTrue(
        result.sameElements(secretBytes),
        encryptCall._1.sameElements(secretBytes),
        created === expectedClient,
      )
    },
    test("updateClient maps request to repository call") {
      val env = new Env()
      val consentFlow = ConsentFlowDto(allowPartial = false, rememberDuration = Some(30.days.toSeconds))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(
          updateRequest.copy(consentFlow = Some(Patch.Modified(consentFlow))),
        )
      yield assertTrue(
        env.repository.updateClient.calls == List(
          (
            clientId,
            Some(Map("en" -> "Updated Web App")),
            updateRequest.redirectUris,
            updateRequest.scope,
            updateRequest.permissions,
            Some(900.seconds),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            Some(Patch.Modified(ConsentFlow(allowPartial = false, rememberDuration = Some(30.days)))),
          ),
        ),
      )
    },
    test("getAllClients returns everything in the cache") {
      val env = new Env(Vector(cachedClient, otherTenantClient))
      for result <- env.service.getAllClients
      yield assertTrue(result == Vector(cachedClient, otherTenantClient))
    },
    test("registerClient accepts a valid https logoUri, policyUri and tosUri") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(
          logoUri = Some("https://example.com/logo.png"),
          policyUri = Some("https://example.com/policy"),
          tosUri = Some("https://example.com/tos"),
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.logoUri == Some("https://example.com/logo.png"),
        created.policyUri == Some("https://example.com/policy"),
        created.tosUri == Some("https://example.com/tos"),
      )
    },
    test("registerClient rejects a non-HTTPS logoUri instead of silently dropping it") {
      val env = new Env()

      for
        result <- env.service.registerClient(createRequest.copy(logoUri = Some("http://example.com/logo.png"))).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "logoUri"
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient rejects a malformed policyUri instead of silently dropping it") {
      val env = new Env()

      for
        result <- env.service.registerClient(createRequest.copy(policyUri = Some("not a url"))).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "policyUri"
          case _ => false,
        createCalls == 0,
      )
    },
    test("updateClient rejects a non-HTTPS tosUri patch instead of silently dropping it") {
      val env = new Env()

      for
        result <- env.service.updateClient(updateRequest.copy(tosUri = Some(Patch.Modified("http://example.com/tos")))).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "tosUri"
          case _ => false,
        updateCalls == 0,
      )
    },
    test("registerClient accepts an https frontChannelLogoutUri") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(frontChannelLogoutUri = Some("https://rp.example.com/front-logout")))
        created = env.repository.createClient.calls.head
      yield assertTrue(created.frontChannelLogoutUri.map(_.encode) == Some("https://rp.example.com/front-logout"))
    },
    test("registerClient accepts an http://localhost backChannelLogoutUri") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(backChannelLogoutUri = Some("http://localhost:3000/back-logout")))
        created = env.repository.createClient.calls.head
      yield assertTrue(created.backChannelLogoutUri.map(_.encode) == Some("http://localhost:3000/back-logout"))
    },
    test("registerClient rejects a non-HTTPS, non-localhost frontChannelLogoutUri instead of silently dropping it") {
      val env = new Env()

      for
        result <- env.service
          .registerClient(createRequest.copy(frontChannelLogoutUri = Some("http://rp.example.com/front-logout")))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "frontChannelLogoutUri"
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient rejects a malformed backChannelLogoutUri instead of silently dropping it") {
      val env = new Env()

      for
        result <- env.service
          .registerClient(createRequest.copy(backChannelLogoutUri = Some("not a url")))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "backChannelLogoutUri"
          case _ => false,
        createCalls == 0,
      )
    },
    test("updateClient rejects a non-HTTPS, non-localhost frontChannelLogoutUri instead of silently dropping it") {
      val env = new Env()

      for
        result <- env.service
          .updateClient(updateRequest.copy(frontChannelLogoutUri = Some(Patch.Modified("http://rp.example.com/front-logout"))))
          .either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "frontChannelLogoutUri"
          case _ => false,
        updateCalls == 0,
      )
    },
    test("registerClient rejects a relative frontChannelLogoutUri instead of silently dropping it") {
      val env = new Env()

      for
        result <- env.service.registerClient(createRequest.copy(frontChannelLogoutUri = Some("/relative/front-logout"))).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "frontChannelLogoutUri"
          case _ => false,
        createCalls == 0,
      )
    },
    test("updateClient clears frontChannelLogoutUri and consentFlow when the patch is an explicit deletion") {
      val env = new Env()

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(
          updateRequest.copy(
            frontChannelLogoutUri = Some(Patch.Deleted),
            consentFlow = Some(Patch.Deleted),
          ),
        )
        (_, _, _, _, _, _, _, _, _, _, _, frontChannelLogoutUri, _, _, _, _, _, consentFlow) = env.repository.updateClient.calls.head
      yield assertTrue(frontChannelLogoutUri == Some(Patch.Deleted), consentFlow == Some(Patch.Deleted))
    },
    test("updateClient stores a frontChannelLogoutUri with surrounding whitespace instead of clearing it") {
      val env = new Env()

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(
          updateRequest.copy(frontChannelLogoutUri = Some(Patch.Modified(" https://rp.example.com/front-logout "))),
        )
        patched = env.repository.updateClient.calls.head._12
      yield assertTrue(patched == Some(Patch.Modified(URL.decode("https://rp.example.com/front-logout").toOption.get)))
    },
    test("registerClient rejects a registration flow granting an unknown role") {
      val env = new Env()

      for
        _ <- env.roleRepository.findRole.succeedsWith(None)
        result <- env.service
          .registerClient(createRequest.copy(registrationFlow = Some(RegistrationFlow.default)))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("does not exist")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient accepts a registration flow granting an existing role") {
      val env = new Env()
      val role = RoleRecord(
        RegistrationFlow.defaultRoleId,
        tenantId,
        Map("en" -> "User"),
        Set.empty,
        active = true,
      )

      for
        _ <- env.roleRepository.findRole.succeedsWith(Some(role))
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(registrationFlow = Some(RegistrationFlow.default)))
        created = env.repository.createClient.calls.head
      yield assertTrue(created.registrationFlow == Some(RegistrationFlow.default))
    },
    test("registerClient rejects registration for a login+password flow") {
      val env = new Env()
      val loginFlow = AuthFlow.default.copy(
        primary = AuthFlow.default.primary.copy(credentials = List(PrimaryCredential.login), inlinePassword = true),
      )

      for
        result <- env.service
          .registerClient(createRequest.copy(authFlow = Some(loginFlow), registrationFlow = Some(RegistrationFlow.default)))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("login+password")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient rejects registration when the credential card asks for a password inline") {
      val env = new Env()
      val inlineFlow = AuthFlow.default.copy(
        primary = AuthFlow.default.primary.copy(
          credentials = List(PrimaryCredential.phone),
          inlinePassword = true,
        ),
      )

      for
        result <- env.service
          .registerClient(createRequest.copy(authFlow = Some(inlineFlow), registrationFlow = Some(RegistrationFlow.default)))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("password inline")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient rejects registration for a card offering several credentials") {
      val env = new Env()
      val multiFlow = AuthFlow.default.copy(
        primary = AuthFlow.default.primary.copy(
          credentials = List(PrimaryCredential.phone, PrimaryCredential.email),
        ),
      )

      for
        result <- env.service
          .registerClient(createRequest.copy(authFlow = Some(multiFlow), registrationFlow = Some(RegistrationFlow.default)))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("exactly one primary credential")
          case _ => false,
        createCalls == 0,
      )
    },
    test("updateClient validates the registration flow against the stored auth flow") {
      val loginFlow = AuthFlow.default.copy(
        primary = AuthFlow.default.primary.copy(credentials = List(PrimaryCredential.login), inlinePassword = true),
      )
      val env = new Env(Vector(cachedClient.copy(authFlow = Some(loginFlow))))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        result <- env.service
          .updateClient(updateRequest.copy(registrationFlow = Some(Patch.Modified(RegistrationFlow.default))))
          .either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("login+password")
          case _ => false,
        updateCalls == 0,
      )
    },
    test("updateClient clears the registration flow when the patch carries an explicit None") {
      val role = RoleRecord(
        RegistrationFlow.defaultRoleId,
        tenantId,
        Map("en" -> "User"),
        Set.empty,
        active = true,
      )
      val env = new Env(Vector(cachedClient.copy(registrationFlow = Some(RegistrationFlow.default))))

      for
        _ <- env.roleRepository.findRole.succeedsWith(Some(role))
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest.copy(registrationFlow = Some(Patch.Deleted)))
        patched = env.repository.updateClient.calls.head._10
      yield assertTrue(patched == Some(Patch.Deleted))
    },
    test("rotateClientSecret returns new secret and stores encrypted secret") {
      val env = new Env()
      val secretBytes = Array.fill(32)(21.toByte)
      val encryptedBytes = Array.fill(48)(27.toByte)
      val storedSecret = Secret(encryptedBytes)

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(secretBytes)
        _ <- env.securityService.encryptAes256.succeedsWith(encryptedBytes)
        _ <- env.repository.rotateClientSecret.succeedsWith(())
        result <- env.service.rotateClientSecret(clientId)
        rotateCall = env.repository.rotateClientSecret.calls.head
        encryptCall = env.securityService.encryptAes256.calls.head
      yield assertTrue(
        result.sameElements(secretBytes),
        encryptCall._1.sameElements(secretBytes),
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
    test("sync upserts fetched client with decrypted secret for non-delete event") {
      val env = new Env(Vector(cachedClient, otherTenantClient))
      val decryptedBytes = Array.fill(32)(9.toByte)
      val updatedClient = cachedClient.copy(clientName = Map("en" -> "Updated Web App"), permissions = Set(readPermission, writePermission))
      val decryptedClient = updatedClient.copy(secret = Some(Secret(decryptedBytes)))

      for
        _ <- env.securityService.decryptAes256.succeedsWith(decryptedBytes)
        _ <- env.repository.find.succeedsWith(Some(updatedClient))
        _ <- env.service.sync(SyncEvent.ClientsUpdated(clientId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.find.calls === List(clientId),
        cached === Vector(otherTenantClient, decryptedClient), // sorted by ID: mobile-app, web-app
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
    test("verifySecret accepts the central-admin client's current secret") {
      val currentSecret = Secret(Array.fill(32)(1.toByte))
      val adminClient = cachedClient.copy(id = CentralConfig.centralClientId, secret = Some(currentSecret))
      val env = new Env(Vector(adminClient))
      for result <- env.service.verifySecret(currentSecret)
      yield assertTrue(result)
    },
    test("verifySecret accepts the central-admin client's previous secret") {
      val currentSecret = Secret(Array.fill(32)(1.toByte))
      val previousSecret = Secret(Array.fill(32)(2.toByte))
      val adminClient = cachedClient.copy(id = CentralConfig.centralClientId, secret = Some(currentSecret), previousSecret = Some(previousSecret))
      val env = new Env(Vector(adminClient))
      for result <- env.service.verifySecret(previousSecret)
      yield assertTrue(result)
    },
    test("verifySecret rejects a secret that matches neither current nor previous") {
      val adminClient = cachedClient.copy(id = CentralConfig.centralClientId, secret = Some(Secret(Array.fill(32)(1.toByte))))
      val env = new Env(Vector(adminClient))
      for result <- env.service.verifySecret(Secret(Array.fill(32)(9.toByte)))
      yield assertTrue(!result)
    },
    test("verifySecret rejects when no central-admin client is cached") {
      val env = new Env(Vector(cachedClient))
      for result <- env.service.verifySecret(Secret(Array.fill(32)(1.toByte)))
      yield assertTrue(!result)
    },
  )

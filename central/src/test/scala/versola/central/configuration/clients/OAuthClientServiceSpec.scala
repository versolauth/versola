package versola.central.configuration.clients

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsService, MtlsCertificateEncoding, PasskeySettings, SubmissionLimits}
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
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{Curve, ECKey}
import versola.util.{Dpop, JsonWebKeySet, Patch, RedirectUri, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json
import zio.prelude.EqualOps
import zio.test.*

import java.security.interfaces.ECPublicKey
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

  private val keyPairGenerator = java.security.KeyPairGenerator.getInstance("EC")
  keyPairGenerator.initialize(Curve.P_256.toECParameterSpec)
  private val publicKeySet = JsonWebKeySet(
    Json.Obj(
      "keys" -> Json.Arr(
        ECKey.Builder(Curve.P_256, keyPairGenerator.generateKeyPair().getPublic.asInstanceOf[ECPublicKey])
          .keyID("ec-1").build()
          .toJSONString.fromJson[Json.Obj].toOption.get,
      ),
    ),
  )

  /** A key set no `private_key_jwt` assertion could be verified against -- no supported JWS
    * algorithm names P-384 -- but a perfectly good thing to match a certificate's public key
    * against, which is all RFC 8705 §2.2 does with it.
    */
  private val p384KeyPairGenerator = java.security.KeyPairGenerator.getInstance("EC")
  p384KeyPairGenerator.initialize(Curve.P_384.toECParameterSpec)
  private val p384KeySet = JsonWebKeySet(
    Json.Obj(
      "keys" -> Json.Arr(
        ECKey.Builder(Curve.P_384, p384KeyPairGenerator.generateKeyPair().getPublic.asInstanceOf[ECPublicKey])
          .keyID("ec-384").build()
          .toJSONString.fromJson[Json.Obj].toOption.get,
      ),
    ),
  )

  /** An edge signing key as the cache holds it: the private JWK's own bytes, decrypted, with
    * its public half in [[edgeKeySet]] so the registration is one central would have accepted.
    */
  private val edgeKeyPair =
    val generator = java.security.KeyPairGenerator.getInstance("EC")
    generator.initialize(Curve.P_256.toECParameterSpec)
    generator.generateKeyPair()

  private val edgeKey = ECKey
    .Builder(Curve.P_256, edgeKeyPair.getPublic.asInstanceOf[ECPublicKey])
    .privateKey(edgeKeyPair.getPrivate)
    .keyID("edge-1")
    .algorithm(JWSAlgorithm.ES256)
    .build()

  private val storedEdgeSigningKey =
    Secret(edgeKey.toJSONString.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  private val edgeKeySet = JsonWebKeySet(
    Json.Obj("keys" -> Json.Arr(edgeKey.toPublicJWK.toJSONString.fromJson[Json.Obj].toOption.get)),
  )

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
    dpopBoundAccessTokens = false,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.client_secret,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    edgeSigningKey = None,
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
    dpopBoundAccessTokens = false,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.client_secret,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    edgeSigningKey = None,
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
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.client_secret,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
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
    dpopSigningAlgs = None,
    dpopMinRsaKeySize = None,
    authMethod = None,
    mtlsAuth = None,
    certificateBoundAccessTokens = None,
    jwks = None,
  )

  /** A tenant whose reverse proxy terminates mTLS and forwards the certificate, which RFC
    * 8705 §6.5 makes a precondition of registering `mtlsAuth` at all. */
  private val mtlsTerminatingSettings = ChallengeSettingsRecord(
    tenantId = tenantId,
    allowedPrefixes = List.empty,
    submissionLimits = SubmissionLimits.empty,
    otpLength = 6,
    otpResendAfter = 60,
    passkeySettings = PasskeySettings("localhost", "Test", List("http://localhost"), "preferred"),
    authConversationTtlSeconds = 900,
    sessionTtlSeconds = 86400,
    sessionIdleTtlSeconds = None,
    userAgentTtlSeconds = 15552000,
    ipHeader = "X-Real-IP",
    acrVocabulary = None,
    postLogoutRedirectUris = List.empty,
    requireDpopNonce = false,
    mtlsCertificateHeader = Some("ssl-client-cert"),
    mtlsCertificateEncoding = Some(MtlsCertificateEncoding.urlEncodedPem),
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = 300,
  )

  class Env(initial: Vector[OAuthClientRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[OAuthClientRepository]
    val tenantRepository = stub[TenantRepository]
    val roleRepository = stub[RoleRepository]
    val challengeSettingsService = stub[ChallengeSettingsService]
    val secureRandom = stub[SecureRandom]
    val securityService = stub[SecurityService]
    val config = TestCentralConfig.config
    val service = OAuthClientService.Impl(cache, repository, tenantRepository, roleRepository, challengeSettingsService, secureRandom, securityService, config)

    /** The tenant terminates mTLS, so an `mtlsAuth` registration is not refused for the lack
      * of somewhere for a certificate to arrive from. */
    def terminatesMtls: UIO[Unit] =
      challengeSettingsService.getSettings.succeedsWith(Some(mtlsTerminatingSettings))

    /** The tenant's proxy does not forward a certificate -- the case §6.5 registration has to
      * refuse. */
    def terminatesNoMtls: UIO[Unit] =
      challengeSettingsService.getSettings.succeedsWith(Some(mtlsTerminatingSettings.copy(
        mtlsCertificateHeader = None,
        mtlsCertificateEncoding = None,
      )))

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
        dpopBoundAccessTokens = false,
        dpopSigningAlgs = Set.empty,
        dpopMinRsaKeySize = None,
        authMethod = AuthMethod.client_secret,
        mtlsAuth = None,
        certificateBoundAccessTokens = false,
        jwks = None,
        requireSignedRequestObject = false,
        requirePushedAuthorizationRequests = false,
        edgeSigningKey = None,
      )

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(secretBytes)
        _ <- env.securityService.encryptAes256.succeedsWith(encryptedBytes)
        _ <- env.repository.createClient.succeedsWith(())
        result <- env.service.registerClient(createRequest.copy(consentFlow = Some(consentFlow)))
        created = env.repository.createClient.calls.head
        encryptCall = env.securityService.encryptAes256.calls.head
      yield assertTrue(
        result.exists(_.sameElements(secretBytes)),
        encryptCall._1.sameElements(secretBytes),
        created === expectedClient,
      )
    },
    test("registerClient stores no secret for a native client") {
      val env = new Env()

      for
        _ <- env.repository.createClient.succeedsWith(())
        result <- env.service.registerClient(createRequest.copy(authMethod = AuthMethod.none))
        created = env.repository.createClient.calls.head
        generatedSecrets = env.secureRandom.nextBytes.times
        encryptions = env.securityService.encryptAes256.times
      yield assertTrue(
        result.isEmpty,
        created.secret.isEmpty,
        created.isPublic,
        generatedSecrets == 0,
        encryptions == 0,
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
            OAuthClientPatch.empty.copy(
              clientName = Some(Map("en" -> "Updated Web App")),
              redirectUris = updateRequest.redirectUris,
              scope = updateRequest.scope,
              permissions = updateRequest.permissions,
              accessTokenTtl = Some(900.seconds),
              consentFlow = Some(Patch.Modified(ConsentFlow(allowPartial = false, rememberDuration = Some(30.days)))),
            ),
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
    test("registerClient trims an mtlsAuth subject value so a pasted certificate subject still matches") {
      val env = new Env()

      for
        _ <- env.terminatesMtls
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.tls_client_auth,
          mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "  CN=client,O=Example  ")),
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.mtlsAuth == Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=client,O=Example")),
        // registered without the §3.4 flag, yet still bound: §2 implies §3
        !created.certificateBoundAccessTokens,
        created.bindsAccessTokens,
      )
    },
    test("registerClient binds a client that asked for §3 binding without authenticating by certificate") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(certificateBoundAccessTokens = true))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.mtlsAuth.isEmpty,
        created.bindsAccessTokens,
      )
    },
    test("registerClient leaves a plain secret client unbound") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest)
        created = env.repository.createClient.calls.head
      yield assertTrue(!created.bindsAccessTokens)
    },
    test("updateClient trims an mtlsAuth subject value and passes a deletion through untouched") {
      val env = new Env(Vector(cachedClient))

      for
        _ <- env.terminatesMtls
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest.copy(
          authMethod = Some(AuthMethod.tls_client_auth),
          mtlsAuth = Some(Patch.Modified(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, " client.example.com "))),
        ))
        _ <- env.service.updateClient(updateRequest.copy(mtlsAuth = Some(Patch.Deleted)))
        calls = env.repository.updateClient.calls
      yield assertTrue(
        calls.head._2.mtlsAuth == Some(Patch.Modified(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com"))),
        calls(1)._2.mtlsAuth == Some(Patch.Deleted),
      )
    },
    test("registerClient stores a JWK Set the client will authenticate assertions with") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.private_key_jwt,
          jwks = Some(publicKeySet),
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.jwks == Some(publicKeySet),
        // The assertion is the whole credential, so there is no secret to store beside it -
        // one would be a credential no endpoint accepts, kept encrypted at rest for nothing.
        created.secret.isEmpty,
      )
    },
    test("registerClient rejects a tls_client_auth client that also registers keys") {
      val env = new Env()

      for
        _ <- env.terminatesMtls
        result <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.tls_client_auth,
          mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com")),
          jwks = Some(publicKeySet),
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("registers no jwks")
          case _ => false,
        createCalls == 0,
      )
        .label("a second credential for one client is the weaker of the two deciding")
    },
    test("registerClient rejects a credential the registered method would never read") {
      val env = new Env()

      for
        withKeys <- env.service.registerClient(createRequest.copy(jwks = Some(publicKeySet))).either
        withoutKeys <- env.service.registerClient(createRequest.copy(authMethod = AuthMethod.private_key_jwt)).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        withKeys.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("registers no keys")
          case _ => false,
        withoutKeys.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("needs jwks")
          case _ => false,
        createCalls == 0,
      )
        .label("a client_secret client with keys, and a private_key_jwt client without them")
    },
    test("registerClient rejects a key set that could never verify an assertion") {
      val env = new Env()

      for
        result <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.private_key_jwt,
          jwks = Some(JsonWebKeySet(Json.Obj("keys" -> Json.Arr()))),
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.startsWith("jwks")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient stores the request form a client is held to") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.private_key_jwt,
          jwks = Some(publicKeySet),
          requireSignedRequestObject = true,
          requirePushedAuthorizationRequests = true,
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.requireSignedRequestObject,
        created.requirePushedAuthorizationRequests,
      )
    },
    test("registerClient rejects requireSignedRequestObject without the keys to verify one") {
      val env = new Env()

      for
        result <- env.service.registerClient(createRequest.copy(requireSignedRequestObject = true)).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("requireSignedRequestObject needs jwks")
          case _ => false,
        createCalls == 0,
      )
    },
    test("updateClient rejects requireSignedRequestObject turned on for a client with no keys") {
      val env = new Env(Vector(cachedClient))

      for
        result <- env.service.updateClient(updateRequest.copy(requireSignedRequestObject = Some(true))).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("requireSignedRequestObject needs jwks")
          case _ => false,
        updateCalls == 0,
      )
    },
    test("updateClient passes the request form through to the repository") {
      val env = new Env(Vector(cachedClient.copy(
        authMethod = AuthMethod.private_key_jwt,
        secret = None,
        jwks = Some(publicKeySet),
      )))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest.copy(
          requireSignedRequestObject = Some(true),
          requirePushedAuthorizationRequests = Some(true),
        ))
        patch = env.repository.updateClient.calls.head._2
      yield assertTrue(
        patch.requireSignedRequestObject == Some(true),
        patch.requirePushedAuthorizationRequests == Some(true),
      )
    },
    test("registerClient stores the DPoP proof key policy a client is held to") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(
          dpopSigningAlgs = Set(Dpop.Algorithm.ES256),
          dpopMinRsaKeySize = Some(4096),
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.dpopSigningAlgs == Set(Dpop.Algorithm.ES256),
        created.dpopMinRsaKeySize == Some(4096),
      )
    },
    test("registerClient rejects a dpopMinRsaKeySize below the RFC 7518 floor") {
      val env = new Env()

      for
        result <- env.service.registerClient(createRequest.copy(dpopMinRsaKeySize = Some(1024))).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("dpopMinRsaKeySize")
          case _ => false,
        createCalls == 0,
      )
    },
    test("updateClient rejects a dpopMinRsaKeySize lowered below the floor") {
      val env = new Env(Vector(cachedClient))

      for
        result <- env.service.updateClient(
          updateRequest.copy(dpopMinRsaKeySize = Some(Patch.Modified(1024))),
        ).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("dpopMinRsaKeySize")
          case _ => false,
        updateCalls == 0,
      )
    },
    test("updateClient passes the DPoP proof key policy through to the repository") {
      val env = new Env(Vector(cachedClient))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest.copy(
          dpopSigningAlgs = Some(Set(Dpop.Algorithm.PS256)),
          dpopMinRsaKeySize = Some(Patch.Modified(3072)),
        ))
        patch = env.repository.updateClient.calls.head._2
      yield assertTrue(
        patch.dpopSigningAlgs == Some(Set(Dpop.Algorithm.PS256)),
        patch.dpopMinRsaKeySize == Some(Patch.Modified(3072)),
      )
    },
    test("updateClient rejects keys added to a client that already authenticates by certificate") {
      val env = new Env(Vector(cachedClient.copy(
        authMethod = AuthMethod.tls_client_auth,
        secret = None,
        mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com")),
      )))

      for
        _ <- env.terminatesMtls
        result <- env.service.updateClient(updateRequest.copy(
          jwks = Some(Patch.Modified(publicKeySet)),
        )).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("registers no jwks")
          case _ => false,
        updateCalls == 0,
      )
    },
    test("registerClient accepts self_signed_tls_client_auth alongside the keys it matches against") {
      val env = new Env()

      for
        _ <- env.terminatesMtls
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.self_signed_tls_client_auth,
          mtlsAuth = Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
          jwks = Some(publicKeySet),
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.mtlsAuth == Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
        created.jwks == Some(publicKeySet),
        // §2.2 registers no subject value, so there is nothing for normalisation to do to it
        created.bindsAccessTokens,
      )
    },
    // V1030 rewrites every pre-existing mtls_auth row into this shape. The migration writes
    // the discriminator as a literal, so nothing but a test keeps it agreeing with the codec
    // that has to read those rows back.
    test("the shape V1030 migrates a stored tls_client_auth row into is the shape the codec reads") {
      val migrated = """{"type":"tls_client_auth","subjectType":"san_dns","subjectValue":"client.example.com"}"""
      val selfSigned = """{"type":"self_signed_tls_client_auth"}"""
      assertTrue(
        migrated.fromJson[MutualTlsAuth] ==
          Right(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com")),
        selfSigned.fromJson[MutualTlsAuth] == Right(MutualTlsAuth.SelfSignedTlsClientAuth()),
        (MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com"): MutualTlsAuth).toJson == migrated,
      )
    },
    test("registerClient accepts a self_signed_tls_client_auth key set on a curve no assertion could use") {
      val env = new Env()

      for
        _ <- env.terminatesMtls
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.self_signed_tls_client_auth,
          mtlsAuth = Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
          jwks = Some(p384KeySet),
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(created.jwks == Some(p384KeySet))
    },
    test("registerClient still refuses the same key set from a private_key_jwt client") {
      val env = new Env()

      for
        result <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.private_key_jwt,
          jwks = Some(p384KeySet),
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("P-256")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient rejects self_signed_tls_client_auth with no keys to match a certificate against") {
      val env = new Env()

      for
        _ <- env.terminatesMtls
        result <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.self_signed_tls_client_auth,
          mtlsAuth = Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("self_signed_tls_client_auth needs jwks")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient refuses mtlsAuth under a tenant whose proxy forwards no certificate") {
      val env = new Env()

      for
        _ <- env.terminatesNoMtls
        result <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.tls_client_auth,
          mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com")),
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("mtlsCertificateHeader")
          case _ => false,
        createCalls == 0,
      )
        .label("RFC 8705 §6.5: nothing would ever look for this client's certificate")
    },
    test("registerClient refuses mtlsAuth under a tenant with no challenge settings at all") {
      val env = new Env()

      for
        _ <- env.challengeSettingsService.getSettings.succeedsWith(None)
        result <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.self_signed_tls_client_auth,
          mtlsAuth = Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
          jwks = Some(publicKeySet),
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("mtlsCertificateHeader")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient does not consult the tenant's mTLS termination for a client that registers no mtlsAuth") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest)
        lookups = env.challengeSettingsService.getSettings.times
        createCalls = env.repository.createClient.times
      yield assertTrue(lookups == 0, createCalls == 1)
        .label("every other registration would pay for a lookup whose answer it has no use for")
    },
    test("updateClient refuses mtlsAuth added under a tenant whose proxy forwards no certificate") {
      val env = new Env(Vector(cachedClient))

      for
        _ <- env.terminatesNoMtls
        result <- env.service.updateClient(updateRequest.copy(
          authMethod = Some(AuthMethod.tls_client_auth),
          mtlsAuth = Some(Patch.Modified(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com"))),
        )).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("mtlsCertificateHeader")
          case _ => false,
        updateCalls == 0,
      )
    },
    test("updateClient refuses mtlsAuth added without moving the method that would read it") {
      val env = new Env(Vector(cachedClient))

      for
        _ <- env.terminatesMtls
        result <- env.service.updateClient(updateRequest.copy(
          mtlsAuth = Some(Patch.Modified(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com"))),
        )).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("registers no certificate")
          case _ => false,
        updateCalls == 0,
      )
        .label("the stored client_secret still decides, so the certificate would never be looked at")
    },
    test("updateClient leaves a stored mtlsAuth it does not mention alone when the tenant terminates mTLS") {
      val env = new Env(Vector(cachedClient.copy(
        authMethod = AuthMethod.tls_client_auth,
        secret = None,
        mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "client.example.com")),
      )))

      for
        _ <- env.terminatesMtls
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest)
        patch = env.repository.updateClient.calls.head._2
      yield assertTrue(patch.mtlsAuth.isEmpty)
        .label("the effective value is still the stored one, which the tenant still supports")
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
        patch = env.repository.updateClient.calls.head._2
      yield assertTrue(patch.frontChannelLogoutUri == Some(Patch.Deleted), patch.consentFlow == Some(Patch.Deleted))
    },
    test("updateClient stores a frontChannelLogoutUri with surrounding whitespace instead of clearing it") {
      val env = new Env()

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(
          updateRequest.copy(frontChannelLogoutUri = Some(Patch.Modified(" https://rp.example.com/front-logout "))),
        )
        patched = env.repository.updateClient.calls.head._2.frontChannelLogoutUri
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
    // The patch names no signing key, so validating only what it carries would let this
    // through and leave the edge holding a key auth can no longer verify anything against.
    test("updateClient refuses a jwks patch that leaves the stored edge signing key unpublished") {
      val env = new Env(Vector(cachedClient.copy(jwks = Some(edgeKeySet), edgeSigningKey = Some(storedEdgeSigningKey))))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        result <- env.service.updateClient(updateRequest.copy(jwks = Some(Patch.Deleted))).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("edgeSigningKey needs jwks")
          case _ => false,
        updateCalls == 0,
      )
    },
    // The complement, so the rule above cannot become "a client with a signing key can no
    // longer be patched at all".
    test("updateClient leaves a stored edge signing key alone when the patch does not touch jwks") {
      val env = new Env(Vector(cachedClient.copy(authMethod = AuthMethod.private_key_jwt, jwks = Some(edgeKeySet), edgeSigningKey = Some(storedEdgeSigningKey))))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        result <- env.service.updateClient(updateRequest).either
        patched = env.repository.updateClient.calls.head._2.edgeSigningKey
      yield assertTrue(result.isRight, patched.isEmpty)
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
        patched = env.repository.updateClient.calls.head._2.registrationFlow
      yield assertTrue(patched == Some(Patch.Deleted))
    },
    test("rotateClientSecret returns new secret and stores encrypted secret") {
      val env = new Env()
      val secretBytes = Array.fill(32)(21.toByte)
      val encryptedBytes = Array.fill(48)(27.toByte)
      val storedSecret = Secret(encryptedBytes)

      for
        _ <- env.repository.find.succeedsWith(Some(cachedClient))
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
        _ <- env.repository.find.succeedsWith(Some(cachedClient))
        _ <- env.repository.deletePreviousClientSecret.succeedsWith(())
        _ <- env.repository.deleteClient.succeedsWith(())
        _ <- env.service.deletePreviousClientSecret(clientId)
        _ <- env.service.deleteClient(clientId)
      yield assertTrue(
        env.repository.deletePreviousClientSecret.calls === List(clientId),
        env.repository.deleteClient.calls === List(clientId),
      )
    },
    test("rotateClientSecret is rejected for a public client") {
      val env = new Env()

      for
        _ <- env.repository.find.succeedsWith(Some(cachedClient.copy(authMethod = AuthMethod.none, secret = None)))
        result <- env.service.rotateClientSecret(clientId).either
        rotations = env.repository.rotateClientSecret.times
      yield assertTrue(
        result == Left(ClientHasNoSecret(clientId)),
        rotations == 0,
      )
    },
    test("rotateClientSecret is rejected for a confidential client that authenticates some other way") {
      val env = new Env()

      for
        _ <- env.repository.find.succeedsWith(Some(cachedClient.copy(
          authMethod = AuthMethod.private_key_jwt,
          secret = None,
          jwks = Some(publicKeySet),
        )))
        result <- env.service.rotateClientSecret(clientId).either
        rotations = env.repository.rotateClientSecret.times
      yield assertTrue(
        result == Left(ClientHasNoSecret(clientId)),
        rotations == 0,
      )
        .label("a rotated secret the token endpoint refuses reads exactly like one it accepts")
    },
    test("deletePreviousClientSecret is rejected for a public client") {
      val env = new Env()

      for
        _ <- env.repository.find.succeedsWith(Some(cachedClient.copy(authMethod = AuthMethod.none, secret = None)))
        result <- env.service.deletePreviousClientSecret(clientId).either
        deletions = env.repository.deletePreviousClientSecret.times
      yield assertTrue(
        result == Left(ClientHasNoSecret(clientId)),
        deletions == 0,
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

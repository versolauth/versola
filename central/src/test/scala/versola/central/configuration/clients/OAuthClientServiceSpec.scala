package versola.central.configuration.clients

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsService, SecurityProfile, MtlsCertificateEncoding, PasskeySettings, SubmissionLimits}
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
import versola.util.{ClientAssertion, EcKeyPair, EnvName, Dpop, JsonWebKeySet, Patch, PrivateClientCertificate, PrivateJsonWebKey, RedirectUri, ReloadingCache, Secret, SecureRandom, SecurityService, TestCertificates}
import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json
import zio.prelude.EqualOps
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.ECGenParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.time.Instant

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

  /** The certificate an edge is provisioned with for a client recognised by RFC 8705 §2.2,
    * and the client as it is stored: the registered PEM, encrypted at rest. */
  private val edgeCertificate = TestCertificates.generate()

  private val storedEdgeClientCertificate =
    Secret(edgeCertificate.bundle.getBytes(java.nio.charset.StandardCharsets.UTF_8))

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
    edgeClientCertificate = None,
    template = None,
    createdAt = Instant.EPOCH,
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
    edgeClientCertificate = None,
    template = None,
    createdAt = Instant.EPOCH,
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
    generateJwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    template = None,
    applicationType = None,
    issueEdgeClientCertificate = false,
    enrollEdgeClientCertificate = false,
  )

  /** Stands in for the pair `SecurityService` would mint, so that what the tests exercise is
    * how registration builds a key set and hands the private half back, not `KeyPairGenerator`. */
  private val generatedEcKeyPair: EcKeyPair =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val pair = generator.generateKeyPair()
    EcKeyPair(
      keyId = "2026-09-29_10-00-00",
      publicKey = pair.getPublic.asInstanceOf[ECPublicKey],
      privateKey = pair.getPrivate.asInstanceOf[ECPrivateKey],
    )

  /** A service calling on its own behalf in a FAPI 2.0 tenant: no redirect URIs, so no PAR is
    * asked of it, and a DPoP-bound token, which is the only sender constraint left to a client
    * that registers no certificate. Its credential is the key registration generates. */
  private val serviceClientRequest = createRequest.copy(
    redirectUris = Set.empty,
    authMethod = AuthMethod.private_key_jwt,
    generateJwks = Some(ClientAssertion.Algorithm.ES256),
    dpopBoundAccessTokens = true,
    accessTokenTtl = 3600,
    template = Some(ClientTemplate(ClientKind.service)),
  )

  /** What FAPI 2.0 asks of an edge-fronted web client: `tls_client_auth`, which also binds its
    * tokens to the certificate, behind PAR, redirecting over https. */
  private val fapi2Request = createRequest.copy(
    authMethod = AuthMethod.tls_client_auth,
    mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "web.example.com")),
    requirePushedAuthorizationRequests = true,
  )

  private val noopUpdate = UpdateClientRequest(
    clientId = clientId,
    clientName = None,
    redirectUris = PatchClientRedirectUris(Set.empty, Set.empty),
    scope = PatchClientScope(Set.empty, Set.empty),
    permissions = PatchPermissions(Set.empty, Set.empty),
    accessTokenTtl = None,
    refreshTokenTtl = None,
    theme = None,
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = None,
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = None,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = None,
    dpopSigningAlgs = None,
    dpopMinRsaKeySize = None,
    authMethod = None,
    mtlsAuth = None,
    certificateBoundAccessTokens = None,
    jwks = None,
    requireSignedRequestObject = None,
    requirePushedAuthorizationRequests = None,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    applicationType = None,
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
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = None,
    dpopSigningAlgs = None,
    dpopMinRsaKeySize = None,
    authMethod = None,
    mtlsAuth = None,
    certificateBoundAccessTokens = None,
    jwks = None,
    requireSignedRequestObject = None,
    requirePushedAuthorizationRequests = None,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    applicationType = None,
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
    securityProfile = SecurityProfile.fapi2,
  )

  /** #421: the registration an edge-fronted native app is held to -- `tls_client_auth` with a
    * certificate edge holds, PAR, DPoP-bound tokens, https App Link redirect. */
  private def edgeFrontedNativeRequest(certificate: TestCertificates.Generated): CreateClientRequest =
    createRequest.copy(
      applicationType = Some(ApplicationType.native),
      authMethod = AuthMethod.tls_client_auth,
      mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, certificate.subjectDn)),
      edgeClientCertificate = Some(PrivateClientCertificate(certificate.bundle)),
      requirePushedAuthorizationRequests = true,
      dpopBoundAccessTokens = true,
      accessTokenTtl = 3600,
      redirectUris = Set(RedirectUri("https://app.example.com/callback")),
    )

  private val clientCa = TestCertificates.generate(subject = "CN=Versola Test Client CA", ca = true)

  private val testCertificateAuthority =
    ClientCertificateAuthority.fromPem(clientCa.certificatePem, clientCa.privateKeyPem, 30, Instant.now()).toOption.get

  class Env(
      initial: Vector[OAuthClientRecord] = Vector.empty,
      envName: EnvName = EnvName.Prod,
      certificateAuthority: ClientCertificateAuthority = testCertificateAuthority,
  ):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[OAuthClientRepository]
    val tenantRepository = stub[TenantRepository]
    val roleRepository = stub[RoleRepository]
    val challengeSettingsService = stub[ChallengeSettingsService]
    val secureRandom = stub[SecureRandom]
    val securityService = stub[SecurityService]
    val config = TestCentralConfig.config
    val service = OAuthClientService.Impl(cache, repository, tenantRepository, roleRepository, challengeSettingsService, secureRandom, securityService, certificateAuthority, config, envName)

    // Every test not about the security profile registers under a `standard` tenant, so what
    // it asserts is not decided by a profile it never mentions.
    challengeSettingsService.getSecurityProfile.returnsWith(ZIO.succeed(SecurityProfile.standard))

    /** The tenant asserts FAPI 2.0 and terminates mTLS. */
    def onFapi2: UIO[Unit] =
      terminatesMtls *> challengeSettingsService.getSecurityProfile.succeedsWith(SecurityProfile.fapi2)

    /** The tenant terminates mTLS, so an `mtlsAuth` registration is not refused for the lack
      * of somewhere for a certificate to arrive from. */
    def terminatesMtls: UIO[Unit] =
      challengeSettingsService.getMtlsCertificateHeader.succeedsWith(mtlsTerminatingSettings.mtlsCertificateHeader)

    /** The tenant's proxy does not forward a certificate -- the case §6.5 registration has to
      * refuse. */
    def terminatesNoMtls: UIO[Unit] =
      challengeSettingsService.getMtlsCertificateHeader.succeedsWith(None)

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
    // Bootstrap seeds central-admin after this cache has loaded, and its change notification
    // is lost (no listener yet): refreshNow is what makes the seed visible to edge's sync.
    test("refreshNow replaces the cache with the repository's clients, secrets decrypted") {
      val edgeId = EdgeId("edge-1")
      val seeded = cachedClient.copy(id = ClientId("central-admin"), secret = Some(Secret.fromString("encrypted")))
      val plaintext = "plaintext".getBytes(java.nio.charset.StandardCharsets.UTF_8)
      val env = new Env(Vector.empty) // loaded before the seed

      for
        _ <- env.repository.getAll.succeedsWith(Vector(seeded))
        _ <- env.securityService.decryptAes256.succeedsWith(plaintext)
        _ <- env.tenantRepository.getAll.succeedsWith(Vector(TenantRecord(tenantId, "Tenant A", Some(edgeId))))
        before <- env.service.getClientsForSync(Some(edgeId))
        _ <- env.service.refreshNow
        after <- env.service.getClientsForSync(Some(edgeId))
      yield assertTrue(
        before.isEmpty,
        after === Vector(seeded.copy(secret = Some(Secret(plaintext)))),
      )
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
        edgeClientCertificate = None,
        template = None,
        createdAt = Instant.EPOCH,
      )

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(secretBytes)
        _ <- env.securityService.encryptAes256.succeedsWith(encryptedBytes)
        _ <- env.repository.createClient.succeedsWith(())
        result <- env.service.registerClient(createRequest.copy(consentFlow = Some(consentFlow)))
        created = env.repository.createClient.calls.head
        encryptCall = env.securityService.encryptAes256.calls.head
      yield assertTrue(
        result.secret.exists(_.sameElements(secretBytes)),
        encryptCall._1.sameElements(secretBytes),
        created === expectedClient,
      )
    },
    test("registerClient stores the template the registration named, and the time it arrived") {
      val env = new Env()
      val template = ClientTemplate(ClientKind.device)

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- TestClock.setTime(Instant.parse("2026-02-01T09:00:00Z"))
        result <- env.service.registerClient(createRequest.copy(template = Some(template)))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.template.contains(template),
        created.createdAt == Instant.parse("2026-02-01T09:00:00Z"),
        // Handed back as well as stored: the caller holding the client it just sent has no
        // other way to know when central dated it.
        result.createdAt == created.createdAt,
      )
    },
    test("registerClient leaves the template unset for a registration that names none") {
      val env = new Env()

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest)
        created = env.repository.createClient.calls.head
      yield assertTrue(created.template.isEmpty)
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
        result.secret.isEmpty,
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
        _ <- env.repository.find.succeedsWith(None)
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
      // The method is the client's own, as registered: a patch cannot move it onto one.
      val mtlsClient = cachedClient.copy(
        authMethod = AuthMethod.tls_client_auth,
        mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "old.example.com")),
      )
      val env = new Env(Vector(mtlsClient, cachedClient.copy(id = ClientId("secret-client"))))

      for
        _ <- env.terminatesMtls
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest.copy(
          mtlsAuth = Some(Patch.Modified(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, " client.example.com "))),
        ))
        _ <- env.service.updateClient(updateRequest.copy(clientId = ClientId("secret-client"), mtlsAuth = Some(Patch.Deleted)))
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
        _ <- env.challengeSettingsService.getMtlsCertificateHeader.succeedsWith(None)
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
        lookups = env.challengeSettingsService.getMtlsCertificateHeader.times
        createCalls = env.repository.createClient.times
      yield assertTrue(lookups == 0, createCalls == 1)
        .label("every other registration would pay for a lookup whose answer it has no use for")
    },
    test("updateClient refuses mtlsAuth added under a tenant whose proxy forwards no certificate") {
      val env = new Env(Vector(cachedClient.copy(authMethod = AuthMethod.tls_client_auth)))

      for
        _ <- env.terminatesNoMtls
        result <- env.service.updateClient(updateRequest.copy(
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
    test("updateClient refuses a patch that names another method than the client registered with") {
      val env = new Env(Vector(cachedClient))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        result <- env.service.updateClient(updateRequest.copy(authMethod = Some(AuthMethod.private_key_jwt))).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration =>
            error.reason.contains("authMethod is client_secret and cannot be changed to private_key_jwt")
          case _ => false,
        updateCalls == 0,
      )
    },
    test("updateClient accepts a patch that restates the method the client registered with") {
      val env = new Env(Vector(cachedClient))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest.copy(authMethod = Some(cachedClient.authMethod)))
        updateCalls = env.repository.updateClient.times
      yield assertTrue(updateCalls == 1)
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
    test("updateClient refuses the same mtlsAuth patch when the client has not yet reached the cache") {
      // A client registered a moment ago may not have reached the cache yet -- exactly the miss
      // `rejectSecretlessClient` already guards against elsewhere. Falling back to the repository
      // is what keeps this the same rejection as the populated-cache case above, rather than one
      // that lets an unvalidated patch through because the cache merely hadn't caught up.
      val env = new Env()

      for
        _ <- env.repository.find.succeedsWith(Some(cachedClient))
        _ <- env.securityService.decryptAes256.succeedsWith(Array.fill(32)(1.toByte))
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
        .label("a cache miss must not read as \"no such client\" and skip validation")
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
    test("registerClient rejects a redirect URI with a private-use scheme (FAPI 2.0 §5.3.2.2)") {
      val env = new Env()

      for
        _ <- env.challengeSettingsService.getSecurityProfile.succeedsWith(SecurityProfile.fapi2)
        result <- env.service
          .registerClient(createRequest.copy(redirectUris = Set(redirectUri1, RedirectUri("com.example.app://callback"))))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "redirectUris"
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient rejects a private-use scheme for a tenant with no settings, as the default profile is fapi2") {
      val env = new Env()

      for
        _ <- env.challengeSettingsService.getSecurityProfile
          .succeedsWith(ChallengeSettingsRecord.DefaultSecurityProfile)
        result <- env.service
          .registerClient(createRequest.copy(redirectUris = Set(RedirectUri("com.example.app://callback"))))
          .either
      yield assertTrue(result.left.toOption.exists(_.isInstanceOf[InvalidConsentUri]))
    },
    // The profile is read from the repository, not the settings cache: a registration into a
    // tenant created a moment ago is held to the profile it was created with, even while the
    // cache still carries no settings, or stale ones, for it.
    test("registerClient holds a private-use scheme to the stored profile, not the cached settings") {
      val env = new Env()

      for
        _ <- env.challengeSettingsService.getSecurityProfile.succeedsWith(SecurityProfile.fapi2)
        result <- env.service
          .registerClient(createRequest.copy(redirectUris = Set(RedirectUri("com.example.app://callback"))))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(result.left.toOption.exists(_.isInstanceOf[InvalidConsentUri]), createCalls == 0)
    },
    test("registerClient accepts a reverse-domain private-use scheme for a standard-profile tenant") {
      val env = new Env()
      val uris = Set(redirectUri1, RedirectUri("com.example.app://callback"))

      for
        _ <- env.challengeSettingsService.getSecurityProfile.succeedsWith(SecurityProfile.standard)
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(redirectUris = uris))
        created = env.repository.createClient.calls.head
      yield assertTrue(created.redirectUris == uris)
    },
    test("registerClient rejects plain http to a non-loopback host even for a standard-profile tenant") {
      val env = new Env()

      for
        _ <- env.challengeSettingsService.getSecurityProfile.succeedsWith(SecurityProfile.standard)
        result <- env.service
          .registerClient(createRequest.copy(redirectUris = Set(RedirectUri("http://rp.example.com/callback"))))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(result.left.toOption.exists(_.isInstanceOf[InvalidConsentUri]), createCalls == 0)
    },
    test("registerClient rejects a plain-http redirect URI to a non-loopback host") {
      val env = new Env()

      for
        _ <- env.challengeSettingsService.getSecurityProfile
          .succeedsWith(ChallengeSettingsRecord.DefaultSecurityProfile)
        result <- env.service
          .registerClient(createRequest.copy(redirectUris = Set(RedirectUri("http://rp.example.com/callback"))))
          .either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "redirectUris"
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient accepts https and loopback-http redirect URIs") {
      val env = new Env()
      val uris = Set(
        redirectUri1,
        RedirectUri("http://localhost:3000/callback"),
        RedirectUri("http://127.0.0.1:51004/callback"),
        RedirectUri("http://[::1]:51004/callback"),
      )

      for
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(redirectUris = uris))
        created = env.repository.createClient.calls.head
      yield assertTrue(created.redirectUris == uris)
    },
    test("updateClient rejects adding a redirect URI with a private-use scheme") {
      val env = new Env(Vector(cachedClient))

      for
        _ <- env.challengeSettingsService.getSecurityProfile.succeedsWith(SecurityProfile.fapi2)
        result <- env.service
          .updateClient(updateRequest.copy(redirectUris =
            PatchClientRedirectUris(add = Set(RedirectUri("com.example.app://callback")), remove = Set.empty),
          ))
          .either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidConsentUri => error.field == "redirectUris"
          case _ => false,
        updateCalls == 0,
      )
    },
    test("updateClient still removes a legacy private-use redirect URI registered before the rule") {
      val env = new Env()
      val legacy = RedirectUri("versola://callback")

      for
        _ <- env.repository.find.succeedsWith(None)
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(updateRequest.copy(redirectUris =
          PatchClientRedirectUris(add = Set(redirectUri2), remove = Set(legacy)),
        ))
        patch = env.repository.updateClient.calls.head._2
      yield assertTrue(patch.redirectUris.remove == Set(legacy))
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
        _ <- env.repository.find.succeedsWith(None)
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
        _ <- env.repository.find.succeedsWith(None)
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
    // The certificate half of the same rule: the patch names no certificate, and dropping the
    // jwks a self-signed registration matches it against leaves an edge presenting one that
    // authenticates nothing.
    test("updateClient refuses a jwks patch that unpublishes the stored certificate's key") {
      val env = new Env(Vector(cachedClient.copy(
        authMethod = AuthMethod.self_signed_tls_client_auth,
        mtlsAuth = Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
        jwks = Some(edgeCertificate.jwks),
        edgeClientCertificate = Some(storedEdgeClientCertificate),
      )))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.terminatesMtls
        result <- env.service.updateClient(updateRequest.copy(jwks = Some(Patch.Modified(p384KeySet)))).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration =>
            error.reason.contains("edgeClientCertificate") && error.reason.contains("does not publish")
          case _ => false,
        updateCalls == 0,
      )
    },
    test("updateClient stores a new certificate encrypted rather than as the PEM it was given") {
      val env = new Env(Vector(cachedClient.copy(
        authMethod = AuthMethod.self_signed_tls_client_auth,
        mtlsAuth = Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
        jwks = Some(edgeCertificate.jwks),
      )))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.terminatesMtls
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(32)(9.toByte))
        result <- env.service.updateClient(updateRequest.copy(
          edgeClientCertificate = Some(Patch.Modified(PrivateClientCertificate(edgeCertificate.bundle))),
        )).either
        patched = env.repository.updateClient.calls.head._2.edgeClientCertificate
      yield assertTrue(
        result.isRight,
        patched.exists:
          case Patch.Modified(stored) => !stored.sameElements(storedEdgeClientCertificate)
          case Patch.Deleted => false,
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
    // #421: a native app fronted by edge -- native and confidential at once.
    test("registerClient accepts an edge-fronted native client without a tenant mTLS header") {
      val env = new Env()
      val certificate = TestCertificates.generate(subject = "CN=native-app")

      for
        // No header: the client reaches auth on its own mutual-TLS listener (#417).
        _ <- env.terminatesNoMtls
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        result <- env.service.registerClient(edgeFrontedNativeRequest(certificate))
        created = env.repository.createClient.calls.head
        lookups = env.challengeSettingsService.getMtlsCertificateHeader.times
      yield assertTrue(
        result.secret.isEmpty,
        created.applicationType == ApplicationType.native,
        created.isEdgeFrontedNative,
        created.isConfidential,
        // cnf carries only the device's jkt, never edge's certificate thumbprint.
        !created.bindsAccessTokens,
        lookups == 0,
      )
    },
    // #440: the certificate edge presents, issued by central rather than supplied.
    test("registerClient issues the edge's certificate from central's CA and registers the client by its subject") {
      val env = new Env()
      val request = edgeFrontedNativeRequest(TestCertificates.generate(subject = "CN=unused"))
        .copy(mtlsAuth = None, edgeClientCertificate = None, issueEdgeClientCertificate = true)

      for
        _ <- env.terminatesNoMtls
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        result <- env.service.registerClient(request)
        created = env.repository.createClient.calls.head
        encrypted = env.securityService.encryptAes256.calls.map((plain, _) => String(plain, java.nio.charset.StandardCharsets.UTF_8))
        material = encrypted.flatMap(PrivateClientCertificate(_).material.toOption).headOption
      yield assertTrue(
        created.mtlsAuth == Some(MutualTlsAuth.TlsClientAuth(
          MutualTlsSubjectType.subject_dn,
          s"CN=$clientId,OU=$tenantId,O=Versola",
        )),
        created.edgeClientCertificate.isDefined,
        created.isEdgeFrontedNative,
        // Nothing about the certificate comes back to the caller.
        result.secret.isEmpty,
        result.privateKey.isEmpty,
      ) && assertTrue(
        material.exists(_.subjectDn == s"CN=$clientId,OU=$tenantId,O=Versola"),
        material.exists(_.leaf.getIssuerX500Principal == clientCa.certificate.getSubjectX500Principal),
        material.exists(m => scala.util.Try(m.leaf.verify(clientCa.certificate.getPublicKey)).isSuccess),
      ).label("the certificate stored for edge is the one issued, signed by the CA")
    },
    test("registerClient issues the certificate of an edge-fronted web client too") {
      val env = new Env()

      for
        _ <- env.terminatesMtls
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.tls_client_auth,
          issueEdgeClientCertificate = true,
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        created.mtlsAuth == Some(MutualTlsAuth.TlsClientAuth(
          MutualTlsSubjectType.subject_dn,
          s"CN=$clientId,OU=$tenantId,O=Versola",
        )),
        created.edgeClientCertificate.isDefined,
        created.applicationType == ApplicationType.web,
      )
    },
    test("registerClient refuses to issue a certificate when central has no CA") {
      val env = new Env(certificateAuthority = ClientCertificateAuthority.Unconfigured)

      for
        result <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.tls_client_auth,
          issueEdgeClientCertificate = true,
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("client-certificate-authority")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient refuses to issue a certificate once central's CA has expired") {
      val expiredAt = Instant.parse("2026-01-01T00:00:00Z")
      val expired = new ClientCertificateAuthority:
        override def issue(tenantId: TenantId, clientId: ClientId) =
          ZIO.fail(ClientCertificateAuthority.Expired(expiredAt))
      val env = new Env(certificateAuthority = expired)

      for
        result <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.tls_client_auth,
          issueEdgeClientCertificate = true,
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains(s"expired at $expiredAt")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient refuses to issue a certificate beside a supplied one, before issuing") {
      val env = new Env()
      val certificate = TestCertificates.generate(subject = "CN=native-app")

      for
        result <- env.service.registerClient(
          edgeFrontedNativeRequest(certificate).copy(mtlsAuth = None, issueEdgeClientCertificate = true),
        ).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("cannot be combined with edgeClientCertificate")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient refuses an edge-fronted native client that does not require PAR") {
      val env = new Env()
      val certificate = TestCertificates.generate(subject = "CN=native-app")

      for
        result <- env.service.registerClient(
          edgeFrontedNativeRequest(certificate).copy(requirePushedAuthorizationRequests = false),
        ).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("requirePushedAuthorizationRequests")
          case _ => false,
        createCalls == 0,
      )
    },
    test("registerClient still binds a web tls_client_auth client's tokens to its certificate") {
      val env = new Env()
      val certificate = TestCertificates.generate(subject = "CN=native-app")

      for
        _ <- env.terminatesMtls
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(edgeFrontedNativeRequest(certificate).copy(applicationType = None))
        created = env.repository.createClient.calls.head
      yield assertTrue(created.applicationType == ApplicationType.web, created.bindsAccessTokens)
    },
    // #463: a client whose edge generates the certificate stores none, which an update must not read
    // as a native client missing the one it is required to have.
    test("updateClient keeps an enrolled native client, which stores no certificate, from reading as missing one") {
      val stored = cachedClient.copy(
        applicationType = ApplicationType.native,
        authMethod = AuthMethod.tls_client_auth,
        secret = None,
        previousSecret = None,
        mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=native-app")),
        edgeClientCertificate = None,
        requirePushedAuthorizationRequests = true,
        dpopBoundAccessTokens = true,
        accessTokenTtl = 3600.seconds,
        redirectUris = Set(redirectUri1),
      )
      val env = new Env(Vector(stored))
      val renaming = updateRequest.copy(accessTokenTtl = None, redirectUris = PatchClientRedirectUris(Set.empty, Set.empty))

      for
        _ <- env.repository.updateClient.succeedsWith(())
        withoutEnrolment <- env.service.updateClient(renaming).either
        enrolled <- env.service.updateClient(renaming, edgeCertificateEnrolled = true).either
      yield assertTrue(
        withoutEnrolment.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("edgeClientCertificate")
          case _ => false,
        enrolled.isRight,
      )
    },
    test("updateClient refuses turning DPoP binding off for an edge-fronted native client") {
      val certificate = TestCertificates.generate(subject = "CN=native-app")
      val stored = cachedClient.copy(
        applicationType = ApplicationType.native,
        authMethod = AuthMethod.tls_client_auth,
        secret = None,
        previousSecret = None,
        mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=native-app")),
        edgeClientCertificate = Some(Secret(certificate.bundle.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
        requirePushedAuthorizationRequests = true,
        dpopBoundAccessTokens = true,
        accessTokenTtl = 3600.seconds,
        redirectUris = Set(redirectUri1),
      )
      val env = new Env(Vector(stored))

      for
        result <- env.service.updateClient(
          updateRequest.copy(accessTokenTtl = None, dpopBoundAccessTokens = Some(false)),
        ).either
        updateCalls = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("dpopBoundAccessTokens")
          case _ => false,
        updateCalls == 0,
      )
    },
    test("verifySecret rejects when no central-admin client is cached") {
      val env = new Env(Vector(cachedClient))
      for result <- env.service.verifySecret(Secret(Array.fill(32)(1.toByte)))
      yield assertTrue(!result)
    },
    // ── #353: the tenant's security profile ──────────────────────────────────────────────
    test("registerClient refuses a client_secret client under a fapi2 tenant, naming every reason") {
      val env = new Env()

      for
        _ <- env.onFapi2
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        result <- env.service.registerClient(createRequest).either
        createdTimes = env.repository.createClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration =>
            error.reason.contains("not client_secret") &&
              error.reason.contains("sender-constrained") &&
              error.reason.contains("pushed authorization")
          case _ => false,
        createdTimes == 0,
      )
    },
    test("registerClient accepts an edge-fronted tls_client_auth web client behind PAR under a fapi2 tenant") {
      val env = new Env()

      for
        _ <- env.onFapi2
        _ <- env.repository.createClient.succeedsWith(())
        registered <- env.service.registerClient(fapi2Request)
        createdTimes = env.repository.createClient.times
      yield assertTrue(registered.secret.isEmpty, createdTimes == 1)
    },
    test("registerClient refuses a public client under a fapi2 tenant") {
      val env = new Env()

      for
        _ <- env.onFapi2
        result <- env.service.registerClient(fapi2Request.copy(authMethod = AuthMethod.none, mtlsAuth = None, dpopBoundAccessTokens = true, accessTokenTtl = 3600)).either
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("not none")
          case _ => false,
      )
    },
    test("registerClient refuses a loopback http redirect URI of a web client under a fapi2 tenant in production") {
      val env = new Env()

      for
        _ <- env.onFapi2
        result <- env.service.registerClient(fapi2Request.copy(redirectUris = Set(RedirectUri("http://localhost:9005/complete")))).either
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("https redirect URIs")
          case _ => false,
      )
    },
    test("registerClient admits a loopback http redirect URI of a web client outside production") {
      val env = new Env(envName = EnvName.Test("local"))

      for
        _ <- env.onFapi2
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(fapi2Request.copy(redirectUris = Set(RedirectUri("http://localhost:9005/complete"))))
        createdTimes = env.repository.createClient.times
      yield assertTrue(createdTimes == 1)
    },
    test("registerClient seeds outside the profile only when told to") {
      val env = new Env()

      for
        _ <- env.onFapi2
        _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(32)(11.toByte))
        _ <- env.securityService.encryptAes256.succeedsWith(Array.fill(48)(17.toByte))
        _ <- env.repository.createClient.succeedsWith(())
        _ <- env.service.registerClient(createRequest, enforceSecurityProfile = false)
        createdTimes = env.repository.createClient.times
      yield assertTrue(createdTimes == 1)
    },
    test("updateClient refuses a patch that leaves a pre-existing client_secret client non-conformant") {
      val env = new Env(Vector(cachedClient))

      for
        _ <- env.onFapi2
        result <- env.service.updateClient(noopUpdate.copy(theme = Some("dark"))).either
        updatedTimes = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("not client_secret")
          case _ => false,
        updatedTimes == 0,
      )
    },
    test("updateClient accepts the patch that brings a client into conformance") {
      // The client already authenticates by certificate -- the method it was registered with --
      // and is only short of the PAR the profile asks of it.
      val env = new Env(Vector(cachedClient.copy(
        authMethod = AuthMethod.tls_client_auth,
        secret = None,
        mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "web.example.com")),
        requirePushedAuthorizationRequests = false,
      )))

      for
        _ <- env.onFapi2
        _ <- env.repository.updateClient.succeedsWith(())
        _ <- env.service.updateClient(noopUpdate.copy(requirePushedAuthorizationRequests = Some(true)))
        updatedTimes = env.repository.updateClient.times
      yield assertTrue(updatedTimes == 1)
    },
    test("updateClient reads redirect URIs the patch adds against the profile") {
      val env = new Env(Vector(cachedClient.copy(
        authMethod = AuthMethod.tls_client_auth,
        secret = None,
        mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "web.example.com")),
        requirePushedAuthorizationRequests = true,
      )))

      for
        _ <- env.onFapi2
        result <- env.service.updateClient(noopUpdate.copy(
          redirectUris = PatchClientRedirectUris(add = Set(RedirectUri("http://web.example.com/cb")), remove = Set.empty),
        )).either
        updatedTimes = env.repository.updateClient.times
      yield assertTrue(result.isLeft, updatedTimes == 0)
    },
    // The repository folds `-- remove ++ add`, so a URI named in both sets is stored. Folding
    // it the other way round for the profile check dropped it from the check while the row
    // kept it -- a loopback http redirect that FAPI 2.0 admits only for a native client.
    test("updateClient holds a redirect URI named in both add and remove to the profile") {
      val env = new Env(Vector(cachedClient.copy(
        authMethod = AuthMethod.tls_client_auth,
        secret = None,
        mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "web.example.com")),
        requirePushedAuthorizationRequests = true,
      )))
      val loopback = RedirectUri("http://localhost:9005/complete")

      for
        _ <- env.onFapi2
        result <- env.service.updateClient(noopUpdate.copy(
          redirectUris = PatchClientRedirectUris(add = Set(loopback), remove = Set(loopback)),
        )).either
        updatedTimes = env.repository.updateClient.times
      yield assertTrue(
        result.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("https redirect URIs")
          case _ => false,
        updatedTimes == 0,
      )
    },
    // #421 made `applicationType` where a registration states it is a mobile binary; before
    // this the profile read it off the console's `device` template instead, so a native client
    // registered through the API was refused the loopback redirect RFC 8252 §7.3 grants it.
    test("registerClient reads native off applicationType, not only the console template") {
      val env = new Env()

      for
        _ <- env.onFapi2
        result <- env.service.registerClient(fapi2Request.copy(
          authMethod = AuthMethod.none,
          mtlsAuth = None,
          dpopBoundAccessTokens = true,
          accessTokenTtl = 3600,
          applicationType = Some(ApplicationType.native),
          redirectUris = Set(RedirectUri("http://127.0.0.1:9005/complete")),
        )).either
        reason = result.left.toOption.collect { case error: InvalidRegistrationConfiguration => error.reason }
      yield assertTrue(
        // Public, which a fapi2 tenant refuses -- but not for the loopback redirect URI,
        // which RFC 8252 §7.3 grants a native app.
        reason.exists(_.contains("not none")),
        !reason.exists(_.contains("https redirect URIs")),
      )
    },
    // A FAPI 2.0 tenant admits no `client_secret`, so registration issuing one is no help
    // there: without these, the caller has to arrive already holding a key, and obtaining one
    // is exactly the step that has no answer inside the product.
    test("registerClient generates the key a private_key_jwt client signs with, keeping only its public half") {
      val env = new Env()

      for
        _ <- env.securityService.generateEcKeyPair.succeedsWith(generatedEcKeyPair)
        _ <- env.repository.createClient.succeedsWith(())
        registered <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.private_key_jwt,
          generateJwks = Some(ClientAssertion.Algorithm.ES256),
        ))
        created = env.repository.createClient.calls.head
      yield assertTrue(
        // The half that was handed back verifies against the half that was stored -- the
        // client could not authenticate with it otherwise.
        registered.privateKey.exists: key =>
          created.jwks.exists(keySet => PrivateJsonWebKey.publishedIn(key, keySet).isRight),
        // `JsonWebKeySet.validate` refuses private key material, so passing it is what says
        // the stored set carries none.
        created.jwks.exists(keySet => JsonWebKeySet.validateForAssertions(keySet.document).isRight),
        created.authMethod == AuthMethod.private_key_jwt,
        created.secret.isEmpty,
      )
    },
    test("registerClient refuses generateJwks alongside jwks, or for a method that reads neither") {
      val env = new Env()

      for
        _ <- env.repository.createClient.succeedsWith(())
        both <- env.service.registerClient(createRequest.copy(
          authMethod = AuthMethod.private_key_jwt,
          jwks = Some(publicKeySet),
          generateJwks = Some(ClientAssertion.Algorithm.ES256),
        )).either
        wrongMethod <- env.service.registerClient(createRequest.copy(
          generateJwks = Some(ClientAssertion.Algorithm.ES256),
        )).either
        createCalls = env.repository.createClient.times
      yield assertTrue(
        both.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("cannot be combined with jwks")
          case _ => false,
        wrongMethod.left.toOption.exists:
          case error: InvalidRegistrationConfiguration => error.reason.contains("which client_secret does not read")
          case _ => false,
        createCalls == 0,
      )
    },
    test("a service client whose key registration generated is admitted by FAPI 2.0") {
      val env = new Env()

      for
        _ <- env.onFapi2
        _ <- env.securityService.generateEcKeyPair.succeedsWith(generatedEcKeyPair)
        _ <- env.repository.createClient.succeedsWith(())
        registered <- env.service.registerClient(serviceClientRequest)
        created = env.repository.createClient.calls.head
        violations = InvalidRegistrationConfiguration.profileViolations(
          SecurityProfile.fapi2,
          InvalidRegistrationConfiguration.ProfileSubject.of(created),
          allowHttpLoopback = false,
        )
      yield assertTrue(
        violations.isEmpty,
        registered.privateKey.isDefined,
      )
    },
  )

package versola.central.configuration.clients

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.*
import versola.central.configuration.clients.certificates.{
  ClientCertificateIssuance,
  ClientCertificateIssuanceRepository,
  ClientCertificateIssuer,
  ClientCertificateService,
  EdgeCertificateEnrollmentRepository,
}
import versola.central.configuration.edges.{EdgeId, EdgeRecord, EdgeService}
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.http.Observability
import versola.util.theme.{BrandColor, Mode, Shape, ThemeOverrides}
import versola.util.{Base64, Base64Url, JWT, Patch, RedirectUri, RsaKeyPair, Secret, SecureRandom, SecurityService, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

object ClientControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val clientId = ClientId("web-app")
  private val redirectUri1 = RedirectUri("https://example.com/callback")
  private val redirectUri2 = RedirectUri("https://example.com/alt-callback")
  private val readScope = ScopeToken("read")
  private val writeScope = ScopeToken("write")
  private val readPermission = Permission("users:read")
  private val writePermission = Permission("users:write")
  private val currentSecret = Secret(Array.fill(48)(1.toByte))
  private val previousSecret = Secret(Array.fill(48)(2.toByte))
  private val rotatedSecret = Secret(Array.fill(32)(3.toByte))
  private val secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES")
  private val edgeId = EdgeId("edge-1")
  private val edgeKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(
      keyId = "client-controller-edge-key",
      publicKey = pair.getPublic.asInstanceOf[RSAPublicKey],
      privateKey = pair.getPrivate.asInstanceOf[java.security.interfaces.RSAPrivateKey],
    )
  private val edgeRecord = EdgeRecord(edgeId, edgeKeyPair.toPublicJwk, None, requireDpopNonce = true)

  private val config = TestCentralConfig.config
  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("a", "b", List("c"), Json.Obj()),
          1.minute,
          JWT.Signature.Symmetric(secretKey),
        ),
      )
      .getOrThrowFiberFailure()
  }

  private val createRequest = CreateClientRequest(
    tenantId = tenantId,
    id = clientId,
    clientName = Map("en" -> "Web App"),
    redirectUris = Set(redirectUri1),
    allowedScopes = Set(readScope),
    permissions = Set.empty,
    accessTokenTtl = 300,
    refreshTokenTtl = Some(7776000),
    theme = "default",
    authFlow = Some(AuthFlow.default),
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

  /** What the web endpoint registers the general request as: its kind recorded. */
  private def registeredAsWeb(request: CreateClientRequest): CreateClientRequest =
    request.copy(template = Some(ClientTemplate(ClientKind.web)), applicationType = Some(ApplicationType.web))

  private val updateRequest = UpdateClientRequest(
    clientId = clientId,
    clientName = Some(Map("en" -> "Updated Web App")),
    redirectUris = PatchClientRedirectUris(
      add = Set(redirectUri2),
      remove = Set(redirectUri1),
    ),
    scope = PatchClientScope(
      add = Set(writeScope),
      remove = Set(readScope),
    ),
    permissions = PatchPermissions(Set.empty, Set.empty),
    accessTokenTtl = Some(900L),
    refreshTokenTtl = None,
    theme = None,
    authFlow = None,
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

  /** A fixed registration time, so the response carries the record's own rather than
    * whatever the clock said when the fixture was built. */
  private val registeredAt = Instant.parse("2026-02-01T09:00:00Z")

  private val clients = Vector(
    OAuthClientRecord(
      id = clientId,
      tenantId = tenantId,
      clientName = Map("en" -> "Web App"),
      redirectUris = Set(redirectUri1),
      scope = Set(readScope),
      secret = Some(currentSecret),
      previousSecret = None,
      accessTokenTtl = 5.minutes,
      refreshTokenTtl = 7776000.seconds,
      permissions = Set(readPermission),
      theme = "",
      authFlow = Some(AuthFlow.default),
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
      template = Some(ClientTemplate(ClientKind.web)),
      createdAt = registeredAt,
    ),
    OAuthClientRecord(
      id = ClientId("mobile-app"),
      tenantId = tenantId,
      clientName = Map("en" -> "Mobile App"),
      redirectUris = Set(redirectUri2),
      scope = Set(writeScope),
      secret = Some(currentSecret),
      previousSecret = Some(previousSecret),
      accessTokenTtl = 10.minutes,
      refreshTokenTtl = 7776000.seconds,
      permissions = Set(writePermission),
      theme = "",
      authFlow = Some(AuthFlow.default),
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
      createdAt = registeredAt,
    ),
  )

  private val createWebRequest = CreateWebClientRequest(
    tenantId = tenantId,
    id = clientId,
    clientName = Map("en" -> "Web App"),
    redirectUris = Set(redirectUri1),
    allowedScopes = Set(readScope),
    accessTokenTtl = 300,
    refreshTokenTtl = Some(7776000),
    authFlow = Some(AuthFlow.default),
    authMethod = AuthMethod.client_secret,
    issueEdgeClientCertificate = false,
    enrollEdgeClientCertificate = false,
  )

  private val partnerTheme = ThemeOverrides(brand = BrandColor.parse("#112233").toOption, shape = Some(Shape.sharp))

  private val createNativeRequest = CreateNativeClientRequest(
    tenantId = tenantId,
    id = ClientId("device-app"),
    clientName = Map("en" -> "Device App"),
    redirectUris = Set(redirectUri1),
    allowedScopes = Set(readScope),
    accessTokenTtl = 300,
    refreshTokenTtl = Some(7776000),
    authFlow = Some(AuthFlow.default),
    authMethod = AuthMethod.none,
    issueEdgeClientCertificate = false,
    enrollEdgeClientCertificate = false,
  )

  private val createServiceRequest = CreateServiceClientRequest(
    tenantId = tenantId,
    id = ClientId("batch-job"),
    clientName = Map("en" -> "Batch Job"),
    allowedScopes = Set(readScope),
    accessTokenTtl = 300,
    authMethod = AuthMethod.client_secret,
  )

  private val nativeRecord = clients.head.copy(
    id = ClientId("device-app"),
    template = Some(ClientTemplate(ClientKind.device)),
    applicationType = ApplicationType.native,
  )

  private val serviceRecord = clients.head.copy(
    id = ClientId("batch-job"),
    redirectUris = Set.empty,
    authFlow = None,
    template = Some(ClientTemplate(ClientKind.service)),
  )

  /** The web endpoint has no `permissions` member: they are a service client's alone. */
  private def webJson(body: String): String =
    body.fromJson[Json.Obj].map(obj => Json.Obj(obj.fields.filterNot(_._1 == "permissions")).toJson).getOrElse(body)

  private def json(method: Method, kind: String, body: String): Request =
    Request(method = method, url = URL.empty / "configuration" / "clients" / kind, body = Body.fromString(body))
      .addHeader(Header.ContentType(MediaType.application.json))

  private def refusedWith(response: Response, text: String): Task[TestResult] =
    response.body.asString.map(body => assertTrue(body.contains(text)))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val securityLayer: ULayer[SecurityService] =
    ZLayer.succeed(new SecurityService:
      override def encryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.succeed(data)
      override def decryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.succeed(data)
      override def encryptRsa(data: Array[Byte], key: java.security.PublicKey) = ZIO.dieMessage("Unused in test")
      override def decryptRsa(data: Array[Byte], key: java.security.PrivateKey) = ZIO.dieMessage("Unused in test")
      override def mac(secret: versola.util.Secret, key: Array[Byte]) = ZIO.dieMessage("Unused in test")
      override def hashPassword(password: versola.util.Secret, salt: versola.util.Salt, pepper: versola.util.Secret.Bytes16) =
        ZIO.dieMessage("Unused in test")
      override def generateRsaKeyPair = ZIO.dieMessage("Unused in test")
      override def generateEcKeyPair = ZIO.dieMessage("Unused in test"))

  /** No certificate is ever issued here: the controller is what is under test, and the real
    * service passes a plain registration straight through to the stubbed [[OAuthClientService]]. */
  private object NoIssuances extends ClientCertificateIssuanceRepository:
    def upsert(issuance: ClientCertificateIssuance) = ZIO.unit
    def replace(issuance: ClientCertificateIssuance) = ZIO.succeed(false)
    def find(clientId: ClientId) = ZIO.none
    def delete(clientId: ClientId) = ZIO.unit
    def expiringBefore(deadline: java.time.Instant) = ZIO.succeed(Vector.empty)

  private object Enrollments:
    def apply(): EdgeCertificateEnrollmentRepository = new EdgeCertificateEnrollmentRepository:
      def enroll(clientId: ClientId, at: Instant) = ZIO.unit
      def isEnrolled(clientId: ClientId) = ZIO.succeed(false)
      def enrolledClients = ZIO.succeed(Set.empty)
      def recordIssued(clientId: ClientId, serial: String, at: Instant, edgeId: EdgeId) = ZIO.unit
      def delete(clientId: ClientId) = ZIO.unit

  private def certificates(service: OAuthClientService): ZEnvironment[ClientCertificateService] =
    ZEnvironment[ClientCertificateService](
      ClientCertificateService.Impl(ClientCertificateIssuer.notConfigured, service, NoIssuances, Enrollments(), config),
    )

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[OAuthClientService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[OAuthClientService], SecurityService) => Task[TestResult] = (_, _, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[OAuthClientService]
        resourceService = stub[versola.central.configuration.resources.ResourceService]
        edgeService = stub[versola.central.configuration.edges.EdgeService]
        tracing <- tracingLayer.build
        security <- securityLayer.build
        securityService = security.get[SecurityService]
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ClientController.routes.provideEnvironment(
              ZEnvironment[OAuthClientService](service) ++ certificates(service) ++ ZEnvironment[
                versola.central.configuration.resources.ResourceService,
              ](resourceService) ++ ZEnvironment[CentralConfig](config) ++ tracing ++ security ++
                ZEnvironment[versola.central.configuration.edges.EdgeService](edgeService),
            ),
          ),
        )
        _ <- service.verifySecret.succeedsWith(true)
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _ => request
        response <- client.batched(requestWithAuth.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service, securityService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def decryptSyncedSecret(value: String, securityService: SecurityService): Task[Secret] =
    for
      encrypted <- ZIO.attempt(Base64.urlDecode(value))
      decrypted <- securityService.decryptAes256(encrypted, config.secretKey)
    yield Secret(decrypted)

  private case class DecryptedSyncOAuthClientRecord(
      id: String,
      clientName: Map[String, String],
      redirectUris: Set[RedirectUri],
      scope: Set[ScopeToken],
      secret: Option[Chunk[Byte]],
      previousSecret: Option[Chunk[Byte]],
      accessTokenTtl: Duration,
      frontChannelLogoutUri: Option[String],
      frontChannelLogoutSessionRequired: Boolean,
  )

  private def decryptSyncedClient(
      client: SyncOAuthClientRecord,
      securityService: SecurityService,
  ): Task[DecryptedSyncOAuthClientRecord] =
    for
      secret <- ZIO.foreach(client.secret)(decryptSyncedSecret(_, securityService))
      previousSecret <- ZIO.foreach(client.previousSecret)(decryptSyncedSecret(_, securityService))
    yield DecryptedSyncOAuthClientRecord(
      id = client.id,
      clientName = client.clientName,
      redirectUris = client.redirectUris,
      scope = client.scope,
      secret = secret.map(Chunk.fromArray),
      previousSecret = previousSecret.map(Chunk.fromArray),
      accessTokenTtl = client.accessTokenTtl,
      frontChannelLogoutUri = client.frontChannelLogoutUri,
      frontChannelLogoutSessionRequired = client.frontChannelLogoutSessionRequired,
    )

  def spec = suite("ClientController")(
    controllerTestCase(
      description = "return tenant clients with pagination params",
      request = Request.get(
        (URL.empty / "configuration" / "clients")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "offset" -> "5", "limit" -> "10")),
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantClients.succeedsWith(clients),
      verify = (response, service, _) =>
        for
          payload <- response.body.asJson[GetAllClientsResponse]
          calls = service.getTenantClients.calls
        yield assertTrue(
          calls == List((tenantId, 5, Some(10))),
          payload == GetAllClientsResponse(
            List(
              OAuthClientResponse(
                id = clientId,
                clientName = Map("en" -> "Web App"),
                redirectUris = Set(redirectUri1),
                scope = Set(readScope),
                permissions = Set(readPermission),
                secretRotation = false,
                authMethod = AuthMethod.client_secret,
                accessTokenTtl = 300L,
                refreshTokenTtl = 7776000L,
                theme = "",
                authFlow = Some(AuthFlow.default),
                otpTemplateId = "default",
                frontChannelLogoutUri = None,
                frontChannelLogoutSessionRequired = false,
                backChannelLogoutUri = None,
                logoUri = None,
                policyUri = None,
                tosUri = None,
                consentFlow = Some(
                  ConsentFlowDto(allowPartial = true, rememberDuration = Some(14.days.toSeconds)),
                ),
                dpopBoundAccessTokens = false,
                dpopSigningAlgs = Set.empty,
                dpopMinRsaKeySize = None,
                mtlsAuth = None,
                certificateBoundAccessTokens = false,
                jwks = None,
                requireSignedRequestObject = false,
                requirePushedAuthorizationRequests = false,
                template = Some(ClientTemplate(ClientKind.web)),
                createdAt = registeredAt,
                applicationType = ApplicationType.web,
                kind = ClientKind.web,
              ),
              OAuthClientResponse(
                id = ClientId("mobile-app"),
                clientName = Map("en" -> "Mobile App"),
                redirectUris = Set(redirectUri2),
                scope = Set(writeScope),
                permissions = Set(writePermission),
                secretRotation = true,
                authMethod = AuthMethod.client_secret,
                accessTokenTtl = 600L,
                refreshTokenTtl = 7776000L,
                theme = "",
                authFlow = Some(AuthFlow.default),
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
                mtlsAuth = None,
                certificateBoundAccessTokens = false,
                jwks = None,
                requireSignedRequestObject = false,
                requirePushedAuthorizationRequests = false,
                template = None,
                createdAt = registeredAt,
                applicationType = ApplicationType.web,
                kind = ClientKind.web,
              ),
            ),
          ),
        ),
    ),
    controllerTestCase(
      description = "use default offset and empty limit when pagination params are absent",
      request = Request.get(
        (URL.empty / "configuration" / "clients")
          .addQueryParam("tenantId", tenantId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getTenantClients.succeedsWith(Vector.empty),
      verify = (response, service, _) =>
        for
          payload <- response.body.asJson[GetAllClientsResponse]
        yield assertTrue(
          service.getTenantClients.calls == List((tenantId, 0, None)),
          payload == GetAllClientsResponse(Nil),
        ),
    ),
    controllerTestCase(
      description = "return synced tenant clients with encrypted secrets for authorized service token",
      request = Request.get(
        (URL.empty / "configuration" / "clients" / "sync")
          .addQueryParam("tenantId", tenantId.toString),
      ).addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getClientsForSync.succeedsWith(clients),
      verify = (response, service, securityService) =>
        for
          payload <- response.body.asJson[GetOAuthClientsSyncResponse]
          decryptedClients <- ZIO.foreach(payload.clients)(decryptSyncedClient(_, securityService))
        yield assertTrue(
          service.getClientsForSync.calls == List(None),
          decryptedClients == Vector(
            DecryptedSyncOAuthClientRecord(
              id = clientId.toString,
              clientName = Map("en" -> "Web App"),
              redirectUris = Set(redirectUri1),
              scope = Set(readScope),
              secret = Some(Chunk.fromArray(currentSecret)),
              previousSecret = None,
              accessTokenTtl = 5.minutes,
              frontChannelLogoutUri = None,
              frontChannelLogoutSessionRequired = false,
            ),
            DecryptedSyncOAuthClientRecord(
              id = "mobile-app",
              clientName = Map("en" -> "Mobile App"),
              redirectUris = Set(redirectUri2),
              scope = Set(writeScope),
              secret = Some(Chunk.fromArray(currentSecret)),
              previousSecret = Some(Chunk.fromArray(previousSecret)),
              accessTokenTtl = 10.minutes,
              frontChannelLogoutUri = None,
              frontChannelLogoutSessionRequired = false,
            ),
          ),
        ),
    ),
    controllerTestCase(
      description = "reject synced tenant clients request without service token",
      request = Request.get(
        (URL.empty / "configuration" / "clients" / "sync")
          .addQueryParam("tenantId", tenantId),
      ),
      expectedStatus = Status.Unauthorized,
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(service.getClientsForSync.calls.isEmpty)),
    ),
    // Gap: getAllClientsSyncEndpoint's edgeId match { case Some(id) => ... } branch (RSA transport
    // encryption for edge-issued tokens) was never exercised; every other sync test uses a
    // central-signed token, which takes the `None` (AES) branch.
    test("return synced tenant clients encrypted via edge RSA key when request is signed by an edge") {
      // Delegates to the real implementation, so the emitted secret is genuine hybrid
      // ciphertext that only the edge's private key can open, and records which RSA public
      // key encryptRsaHybrid wrapped the session key with -- encryptAes256 is exercised too
      // now (the hybrid scheme's own session-key wrapping), so it is no longer the signal
      // that the wrong (central-internal, fixed-key) branch was taken; rsaKeysUsed is.
      def edgeSecurity(realSecurity: SecurityService, rsaKeys: Ref[Vector[java.security.PublicKey]]) = new SecurityService:
        override def encryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = realSecurity.encryptAes256(data, key)
        override def decryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = realSecurity.decryptAes256(data, key)
        override def encryptRsa(data: Array[Byte], key: java.security.PublicKey) =
          rsaKeys.update(_ :+ key) *> realSecurity.encryptRsa(data, key)
        override def decryptRsa(data: Array[Byte], key: java.security.PrivateKey) = realSecurity.decryptRsa(data, key)
        override def mac(secret: versola.util.Secret, key: Array[Byte]) = ZIO.dieMessage("Unused in test")
        override def hashPassword(password: versola.util.Secret, salt: versola.util.Salt, pepper: versola.util.Secret.Bytes16) =
          ZIO.dieMessage("Unused in test")
        override def generateRsaKeyPair = ZIO.dieMessage("Unused in test")
        override def generateEcKeyPair = ZIO.dieMessage("Unused in test")
      for
        client <- ZIO.service[Client]
        security <- (SecureRandom.live >>> SecurityService.live).build
        realSecurity = security.get[SecurityService]
        rsaKeys <- Ref.make(Vector.empty[java.security.PublicKey])
        service = stub[OAuthClientService]
        resourceService = stub[versola.central.configuration.resources.ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        token <- JWT.serialize(
          JWT.Claims("edge", "edge", List("central"), Json.Obj()),
          1.minute,
          JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKeyPair.keyId, edgeKeyPair.privateKey),
          headers = Map("edge_id" -> edgeId.toString),
        )
        _ <- edgeService.find.succeedsWith(Some(edgeRecord))
        _ <- service.getClientsForSync.succeedsWith(Vector(clients.head))
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ClientController.routes.provideEnvironment(
              ZEnvironment[OAuthClientService](service) ++ certificates(service) ++ ZEnvironment[
                versola.central.configuration.resources.ResourceService,
              ](resourceService) ++ ZEnvironment[CentralConfig](config) ++ tracing ++
                ZEnvironment[SecurityService](edgeSecurity(realSecurity, rsaKeys)) ++ ZEnvironment[EdgeService](edgeService),
            ),
          ),
        )
        response <- client.batched(
          Request.get((URL.empty / "configuration" / "clients" / "sync").addQueryParam("tenantId", tenantId.toString))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        payload <- response.body.asJson[GetOAuthClientsSyncResponse]
        rsaKeysUsed <- rsaKeys.get
        wireSecret = payload.clients.head.secret
        recovered <- ZIO.foreach(wireSecret)(s => realSecurity.decryptRsaHybrid(Base64.urlDecode(s), edgeKeyPair.privateKey))
        plaintext = clients.head.secret
      yield assertTrue(
        response.status == Status.Ok,
        // authorizeInternal verifies the token's issuing edge, then the sync endpoint looks
        // up the edge again to pick its RSA public key for transport encryption.
        edgeService.find.calls == List(edgeId, edgeId),
        service.getClientsForSync.calls == List(Some(edgeId)),
        rsaKeysUsed == Vector(edgeRecord.activeRsaPublicKey),
        wireSecret.exists(_ != Base64Url.encode(plaintext.get)),
        recovered.map(_.toVector) == plaintext.map(_.toVector),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    // #463: only an edge asks for a certificate to be signed. The token central itself syncs with
    // names no edge, and has no business here.
    controllerTestCase(
      description = "refuses to sign an edge certificate for a caller that is not an edge",
      request = Request.post(
        URL.empty / "configuration" / "clients" / "edge-certificate" / "sign",
        Body.fromString("""{"clientId":"web-app","csr":"x"}"""),
      ).addHeader(Header.Authorization.Bearer(syncToken)).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Unauthorized,
    ),
    test("answers an edge's request for a client that is not enrolled with 422, and signs nothing") {
      for
        client <- ZIO.service[Client]
        service = stub[OAuthClientService]
        resourceService = stub[versola.central.configuration.resources.ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        security <- securityLayer.build
        token <- JWT.serialize(
          JWT.Claims("edge", "edge", List("central"), Json.Obj()),
          1.minute,
          JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKeyPair.keyId, edgeKeyPair.privateKey),
          headers = Map("edge_id" -> edgeId.toString),
        )
        _ <- edgeService.find.succeedsWith(Some(edgeRecord))
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ClientController.routes.provideEnvironment(
              ZEnvironment[OAuthClientService](service) ++ certificates(service) ++ ZEnvironment[
                versola.central.configuration.resources.ResourceService,
              ](resourceService) ++ ZEnvironment[CentralConfig](config) ++ tracing ++ security ++
                ZEnvironment[EdgeService](edgeService),
            ),
          ),
        )
        response <- client.batched(
          Request.post(
            URL.empty / "configuration" / "clients" / "edge-certificate" / "sign",
            Body.fromString("""{"clientId":"web-app","csr":"x"}"""),
          ).addHeader(Header.Authorization.Bearer(token)).addHeader(Header.ContentType(MediaType.application.json)),
        )
        text <- response.body.asString
      yield assertTrue(response.status == Status.UnprocessableEntity, text.contains("not enrolled"))
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    // Regression test for the bug encryptRsaHybrid fixes: plain RSA-OAEP transport
    // (encryptRsa, what the test above exercises for the 48-byte client secret) throws
    // IllegalBlockSizeException past 190 bytes, and a real edge signing key's JWK document
    // routinely exceeds that -- this endpoint 500'd for any client that had registered one,
    // until it switched to encryptRsaHybrid.
    test("return synced tenant clients with an edgeSigningKey too large for a single RSA block") {
      val oversizedKey = Secret(Array.tabulate(220)(i => (i % 251).toByte))
      val clientWithKey = clients.head.copy(edgeSigningKey = Some(oversizedKey))

      for
        httpClient <- ZIO.service[Client]
        security <- (SecureRandom.live >>> SecurityService.live).build
        realSecurity = security.get[SecurityService]
        service = stub[OAuthClientService]
        resourceService = stub[versola.central.configuration.resources.ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        token <- JWT.serialize(
          JWT.Claims("edge", "edge", List("central"), Json.Obj()),
          1.minute,
          JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKeyPair.keyId, edgeKeyPair.privateKey),
          headers = Map("edge_id" -> edgeId.toString),
        )
        _ <- edgeService.find.succeedsWith(Some(edgeRecord))
        _ <- service.getClientsForSync.succeedsWith(Vector(clientWithKey))
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ClientController.routes.provideEnvironment(
              ZEnvironment[OAuthClientService](service) ++ certificates(service) ++ ZEnvironment[
                versola.central.configuration.resources.ResourceService,
              ](resourceService) ++ ZEnvironment[CentralConfig](config) ++ tracing ++
                security ++ ZEnvironment[EdgeService](edgeService),
            ),
          ),
        )
        response <- httpClient.batched(
          Request.get((URL.empty / "configuration" / "clients" / "sync").addQueryParam("tenantId", tenantId.toString))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        payload <- response.body.asJson[GetOAuthClientsSyncResponse]
        wireKey = payload.clients.head.edgeSigningKey
        recovered <- ZIO.foreach(wireKey)(k => realSecurity.decryptRsaHybrid(Base64.urlDecode(k), edgeKeyPair.privateKey))
      yield assertTrue(
        response.status == Status.Ok,
        recovered.map(_.toVector) == Some(oversizedKey.toVector),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    // The PEM of a certificate and its key is larger again than a JWK document, so this shares
    // the hybrid transport for the same reason -- and must reach an edge and nobody else.
    test("hand an edge the edgeClientCertificate, encrypted to the key only it holds") {
      val certificate = Secret(TestCertificates.generate().bundle.getBytes("UTF-8").nn)
      val clientWithCertificate = clients.head.copy(edgeClientCertificate = Some(certificate))

      for
        httpClient <- ZIO.service[Client]
        security <- (SecureRandom.live >>> SecurityService.live).build
        realSecurity = security.get[SecurityService]
        service = stub[OAuthClientService]
        resourceService = stub[versola.central.configuration.resources.ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        token <- JWT.serialize(
          JWT.Claims("edge", "edge", List("central"), Json.Obj()),
          1.minute,
          JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKeyPair.keyId, edgeKeyPair.privateKey),
          headers = Map("edge_id" -> edgeId.toString),
        )
        _ <- edgeService.find.succeedsWith(Some(edgeRecord))
        _ <- service.getClientsForSync.succeedsWith(Vector(clientWithCertificate))
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ClientController.routes.provideEnvironment(
              ZEnvironment[OAuthClientService](service) ++ certificates(service) ++ ZEnvironment[
                versola.central.configuration.resources.ResourceService,
              ](resourceService) ++ ZEnvironment[CentralConfig](config) ++ tracing ++
                security ++ ZEnvironment[EdgeService](edgeService),
            ),
          ),
        )
        response <- httpClient.batched(
          Request.get((URL.empty / "configuration" / "clients" / "sync").addQueryParam("tenantId", tenantId.toString))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        payload <- response.body.asJson[GetOAuthClientsSyncResponse]
        wire = payload.clients.head.edgeClientCertificate
        recovered <- ZIO.foreach(wire)(value =>
          realSecurity.decryptRsaHybrid(Base64.urlDecode(value), edgeKeyPair.privateKey),
        )
      yield assertTrue(
        response.status == Status.Ok,
        recovered.map(_.toVector) == Some(certificate.toVector),
      )
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging,
    controllerTestCase(
      description = "create client and return encoded secret",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(createRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service =>
        service.registerClient.succeedsWith(RegisteredClient(Some(rotatedSecret), registeredAt, None)),
      verify = (response, service, _) =>
        for
          body <- response.body.asJson[CreateClientResponse]
        yield assertTrue(
          service.registerClient.calls == List((registeredAsWeb(createRequest), None, true)),
          // The registration time is the service's, handed back as it recorded it.
          body == CreateClientResponse(Some(Base64Url.encode(rotatedSecret)), registeredAt, None),
        ),
    ),
    controllerTestCase(
      description = "create client returns 400 when both logout URIs are configured",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(createRequest.copy(
          frontChannelLogoutUri = Some("https://example.com/front-logout"),
          backChannelLogoutUri = Some("https://example.com/back-logout"),
        ).toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.registerClient.calls.isEmpty),
        ),
    ),
    controllerTestCase(
      description = "create client returns 409 when the client id already exists",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(createRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Conflict,
      setup = service =>
        service.registerClient.failsWith(ClientAlreadyExists(clientId)),
    ),
    controllerTestCase(
      description = "create client returns 400 when the consent screen URI is invalid",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(createRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service =>
        service.registerClient.failsWith(InvalidConsentUri("policyUri", "must be an absolute https URL")),
      verify = (response, _, _) =>
        for body <- response.body.asString
        yield assertTrue(body == "Invalid policyUri: must be an absolute https URL"),
    ),
    controllerTestCase(
      description = "create client returns 400 when the registration configuration is invalid",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(createRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service =>
        service.registerClient.failsWith(InvalidRegistrationConfiguration(clientId, "registration must start with OTP verification")),
      verify = (response, _, _) =>
        for body <- response.body.asString
        yield assertTrue(body == "Invalid registration configuration: registration must start with OTP verification"),
    ),
    controllerTestCase(
      description = "create client surfaces an unexpected failure as 500 Internal Server Error",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(createRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.InternalServerError,
      setup = service =>
        service.registerClient.failsWith(RuntimeException("boom")),
    ),
    controllerTestCase(
      description = "update client and return no content",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(updateRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.findClient.succeedsWith(clients.headOption) *> service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.updateClient.calls == List((updateRequest, true, false))),
        ),
    ),
    controllerTestCase(
      description = "update client returns 400 when both logout URIs are configured",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(updateRequest.copy(
          frontChannelLogoutUri = Some(Patch.Modified("https://example.com/front-logout")),
          backChannelLogoutUri = Some(Patch.Modified("https://example.com/back-logout")),
        ).toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.updateClient.calls.isEmpty),
        ),
    ),
    controllerTestCase(
      description = "update client succeeds when setting only one logout URI",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(updateRequest.copy(
          frontChannelLogoutUri = Some(Patch.Modified("https://example.com/front-logout")),
        ).toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.findClient.succeedsWith(clients.headOption) *> service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.updateClient.calls.nonEmpty),
        ),
    ),
    // Gap: isSettingValue's `case Patch.Deleted => false` branch is only reached when a logout
    // URI patch is explicitly nulled out; every other test either omits the field or sets it
    // to a value (Patch.Modified).
    controllerTestCase(
      description = "update client succeeds when clearing a single logout URI via null patch",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(updateRequest.copy(
          frontChannelLogoutUri = Some(Patch.Deleted),
        ).toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.findClient.succeedsWith(clients.headOption) *> service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.updateClient.calls == List((updateRequest.copy(frontChannelLogoutUri = Some(Patch.Deleted)), true, false))),
        ),
    ),
    controllerTestCase(
      description = "update client returns 400 when the consent screen URI is invalid",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(updateRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service =>
        service.findClient.succeedsWith(clients.headOption) *> service.updateClient.failsWith(InvalidConsentUri(
          "policyUri",
          "must be an absolute https URL",
        )),
      verify = (response, _, _) =>
        for body <- response.body.asString
        yield assertTrue(body == "Invalid policyUri: must be an absolute https URL"),
    ),
    controllerTestCase(
      description = "update client returns 400 when the registration configuration is invalid",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(updateRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = service =>
        service.findClient.succeedsWith(clients.headOption) *> service.updateClient.failsWith(InvalidRegistrationConfiguration(
          clientId,
          "registration must start with OTP verification",
        )),
      verify = (response, _, _) =>
        for body <- response.body.asString
        yield assertTrue(body == "Invalid registration configuration: registration must start with OTP verification"),
    ),
    controllerTestCase(
      description = "update client surfaces an unexpected failure as 500 Internal Server Error",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "clients" / "web",
        body = Body.fromString(webJson(updateRequest.toJson)),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.InternalServerError,
      setup = service =>
        service.findClient.succeedsWith(clients.headOption) *> service.updateClient.failsWith(RuntimeException("boom")),
    ),
    controllerTestCase(
      description = "rotate client secret and return encoded value",
      request = Request(
        method = Method.POST,
        url = (URL.empty / "configuration" / "clients" / "rotate-secret")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "clientId" -> clientId.toString)),
      ),
      expectedStatus = Status.Ok,
      setup = service =>
        service.rotateClientSecret.succeedsWith(rotatedSecret),
      verify = (response, service, _) =>
        for
          body <- response.body.asJson[RotateSecretResponse]
        yield assertTrue(
          service.rotateClientSecret.calls == List(clientId),
          body == RotateSecretResponse(Base64Url.encode(rotatedSecret)),
        ),
    ),
    controllerTestCase(
      description = "rotate client secret returns 409 for a native client",
      request = Request(
        method = Method.POST,
        url = (URL.empty / "configuration" / "clients" / "rotate-secret")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "clientId" -> clientId.toString)),
      ),
      expectedStatus = Status.Conflict,
      setup = service =>
        service.rotateClientSecret.failsWith(ClientHasNoSecret(clientId)),
      verify = (response, _, _) =>
        for body <- response.body.asString
        yield assertTrue(body == s"Client '$clientId' has no secret"),
    ),
    controllerTestCase(
      description = "delete previous client secret returns 409 for a native client",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "configuration" / "clients" / "previous-secret")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "clientId" -> clientId.toString)),
      ),
      expectedStatus = Status.Conflict,
      setup = service =>
        service.deletePreviousClientSecret.failsWith(ClientHasNoSecret(clientId)),
    ),
    controllerTestCase(
      description = "delete previous client secret",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "configuration" / "clients" / "previous-secret")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "clientId" -> clientId.toString)),
      ),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.deletePreviousClientSecret.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.deletePreviousClientSecret.calls == List(clientId)),
        ),
    ),
    controllerTestCase(
      description = "delete client",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "configuration" / "clients")
          .addQueryParams(Map("tenantId" -> tenantId.toString, "clientId" -> clientId.toString)),
      ),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.deleteClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(
          assertTrue(service.deleteClient.calls == List(clientId)),
        ),
    ),

    // One registration and one update per kind of client.
    controllerTestCase(
      description = "register a web client: the kind is recorded and the application type is web",
      request = json(Method.POST, "web", createWebRequest.toJson),
      expectedStatus = Status.Created,
      setup = _.registerClient.succeedsWith(RegisteredClient(Some(rotatedSecret), registeredAt, None)),
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(
          service.registerClient.calls == List((createWebRequest.toCreateClientRequest, None, true)),
          createWebRequest.toCreateClientRequest.template == Some(ClientTemplate(ClientKind.web)),
          createWebRequest.toCreateClientRequest.applicationType == Some(ApplicationType.web),
        )),
    ),
    controllerTestCase(
      description = "register a web client without a credential is refused: a web client is confidential",
      request = json(Method.POST, "web", createWebRequest.copy(authMethod = AuthMethod.none).toJson),
      expectedStatus = Status.BadRequest,
      verify = (response, service, _) =>
        refusedWith(response, "web client is confidential").map(_ && assertTrue(service.registerClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "register a web client without a redirect URI is refused",
      request = json(Method.POST, "web", createWebRequest.copy(redirectUris = Set.empty).toJson),
      expectedStatus = Status.BadRequest,
      verify = (response, service, _) =>
        refusedWith(response, "redirect URI").map(_ && assertTrue(service.registerClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "register a public native client: it cannot bind to a certificate or sign a request object, and DPoP is its own choice",
      request = json(Method.POST, "native", createNativeRequest.toJson),
      expectedStatus = Status.Created,
      setup = _.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None)),
      verify = (_, service, _) =>
        ZIO.succeed:
          val registered = service.registerClient.calls.map(_._1)
          assertTrue(
            registered.map(_.dpopBoundAccessTokens) == List(false),
            registered.map(_.certificateBoundAccessTokens) == List(false),
            registered.map(_.requireSignedRequestObject) == List(false),
            registered.map(_.applicationType) == List(Some(ApplicationType.native)),
            registered.map(_.template) == List(Some(ClientTemplate(ClientKind.device))),
          ),
    ),
    controllerTestCase(
      description = "register a native client with a shared secret is refused",
      request = json(Method.POST, "native", createNativeRequest.copy(authMethod = AuthMethod.client_secret).toJson),
      expectedStatus = Status.BadRequest,
      verify = (response, service, _) =>
        refusedWith(response, "public (none) or fronted by edge").map(_ && assertTrue(service.registerClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "a native request that names a setting its kind fixes (PAR) is refused, not silently overridden",
      request = json(
        Method.POST,
        "native",
        createNativeRequest.toJson.stripSuffix("}") + ""","requirePushedAuthorizationRequests":false}""",
      ),
      expectedStatus = Status.BadRequest,
      verify = (_, service, _) => ZIO.succeed(assertTrue(service.registerClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "register a service client: no redirect URIs, sign-in flow or refresh token",
      request = json(Method.POST, "service", createServiceRequest.toJson),
      expectedStatus = Status.Created,
      setup = _.registerClient.succeedsWith(RegisteredClient(Some(rotatedSecret), registeredAt, None)),
      verify = (_, service, _) =>
        ZIO.succeed:
          val registered = service.registerClient.calls.map(_._1)
          assertTrue(
            registered.map(_.redirectUris) == List(Set.empty[RedirectUri]),
            registered.map(_.authFlow) == List(None),
            registered.map(_.refreshTokenTtl) == List(None),
            registered.map(_.requirePushedAuthorizationRequests) == List(false),
            registered.map(_.template) == List(Some(ClientTemplate(ClientKind.service))),
          ),
    ),
    controllerTestCase(
      description = "register a service client that does not authenticate is refused",
      request = json(Method.POST, "service", createServiceRequest.copy(authMethod = AuthMethod.none).toJson),
      expectedStatus = Status.BadRequest,
      verify = (response, service, _) =>
        refusedWith(response, "must authenticate").map(_ && assertTrue(service.registerClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "update a web client at the web endpoint",
      request = json(Method.PUT, "web", UpdateWebClientRequest.of(clientId).copy(accessTokenTtl = Some(900L)).toJson),
      expectedStatus = Status.NoContent,
      setup = service => service.findClient.succeedsWith(clients.headOption) *> service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(
          service.updateClient.calls == List((
            UpdateWebClientRequest.of(clientId).copy(accessTokenTtl = Some(900L)).toUpdateClientRequest,
            true,
            false,
          )),
        )),
    ),
    controllerTestCase(
      description = "update a native client at the web endpoint is a conflict: a client's kind cannot change",
      request = json(Method.PUT, "web", UpdateWebClientRequest.of(nativeRecord.id).copy(accessTokenTtl = Some(900L)).toJson),
      expectedStatus = Status.Conflict,
      setup = _.findClient.succeedsWith(Some(nativeRecord)),
      verify = (response, service, _) =>
        refusedWith(response, "/configuration/clients/native").map(_ && assertTrue(service.updateClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "update an unknown client at a typed endpoint is not found",
      request = json(Method.PUT, "service", UpdateServiceClientRequest.of(ClientId("missing")).toJson),
      expectedStatus = Status.NotFound,
      setup = _.findClient.succeedsWith(None),
      verify = (_, service, _) => ZIO.succeed(assertTrue(service.updateClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "update a web client cannot remove its last redirect URI",
      request = json(
        Method.PUT,
        "web",
        UpdateWebClientRequest.of(clientId).copy(redirectUris = PatchClientRedirectUris(Set.empty, Set(redirectUri1))).toJson,
      ),
      expectedStatus = Status.BadRequest,
      setup = _.findClient.succeedsWith(clients.headOption),
      verify = (response, service, _) =>
        refusedWith(response, "at least one redirect URI").map(_ && assertTrue(service.updateClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "register a web client with theme overrides: they reach the registration, and the body names them as the console sends them",
      request = json(Method.POST, "web", createWebRequest.copy(themeOverrides = Some(partnerTheme)).toJson),
      expectedStatus = Status.Created,
      setup = _.registerClient.succeedsWith(RegisteredClient(Some(rotatedSecret), registeredAt, None)),
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(
          service.registerClient.calls.map(_._1.themeOverrides) == List(Some(partnerTheme)),
          createWebRequest.copy(themeOverrides = Some(partnerTheme)).toJson.contains(""""themeOverrides":{"brand":"#112233","shape":"sharp"}"""),
        )),
    ),
    controllerTestCase(
      description = "register a native client with theme overrides",
      request = json(Method.POST, "native", createNativeRequest.copy(themeOverrides = Some(partnerTheme)).toJson),
      expectedStatus = Status.Created,
      setup = _.registerClient.succeedsWith(RegisteredClient(None, registeredAt, None)),
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(service.registerClient.calls.map(_._1.themeOverrides) == List(Some(partnerTheme)))),
    ),
    controllerTestCase(
      description = "update a web client's theme overrides; an empty value is passed on to clear them",
      request = json(Method.PUT, "web", UpdateWebClientRequest.of(clientId).copy(themeOverrides = Some(ThemeOverrides())).toJson),
      expectedStatus = Status.NoContent,
      setup = service => service.findClient.succeedsWith(clients.headOption) *> service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(service.updateClient.calls.map(_._1.themeOverrides) == List(Some(ThemeOverrides())))),
    ),
    controllerTestCase(
      description = "update a native client's theme overrides",
      request = json(Method.PUT, "native", UpdateNativeClientRequest.of(nativeRecord.id).copy(themeOverrides = Some(partnerTheme)).toJson),
      expectedStatus = Status.NoContent,
      setup = service => service.findClient.succeedsWith(Some(nativeRecord)) *> service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(service.updateClient.calls.map(_._1.themeOverrides) == List(Some(partnerTheme)))),
    ),
    controllerTestCase(
      description = "update a native client fronted by edge brings what its kind fixes up with it, whatever it was stored as",
      request = json(Method.PUT, "native", UpdateNativeClientRequest.of(nativeRecord.id).copy(accessTokenTtl = Some(3600L)).toJson),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.findClient.succeedsWith(
          Some(nativeRecord.copy(authMethod = AuthMethod.tls_client_auth, dpopBoundAccessTokens = false, requirePushedAuthorizationRequests = false)),
        ) *> service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed:
          val sent = service.updateClient.calls.map(_._1)
          assertTrue(
            sent.map(_.dpopBoundAccessTokens) == List(Some(true)),
            sent.map(_.certificateBoundAccessTokens) == List(Some(false)),
            sent.map(_.requirePushedAuthorizationRequests) == List(Some(true)),
          ),
    ),
    controllerTestCase(
      description = "a web or native request that names permissions is refused: they are a service client's alone",
      request = json(Method.POST, "web", createWebRequest.toJson.stripSuffix("}") + ""","permissions":["users:read"]}"""),
      expectedStatus = Status.BadRequest,
      verify = (_, service, _) => ZIO.succeed(assertTrue(service.registerClient.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "update a service client at the service endpoint",
      request = json(Method.PUT, "service", UpdateServiceClientRequest.of(serviceRecord.id).copy(accessTokenTtl = Some(600L)).toJson),
      expectedStatus = Status.NoContent,
      setup = service => service.findClient.succeedsWith(Some(serviceRecord)) *> service.updateClient.succeedsWith(()),
      verify = (_, service, _) =>
        ZIO.succeed(assertTrue(service.updateClient.calls.map(_._1.accessTokenTtl) == List(Some(600L)))),
    ),
    test("a native update request has no field for what the kind fixes, and cannot move the client's method") {
      val fixed = Set("certificateBoundAccessTokens", "requirePushedAuthorizationRequests", "requireSignedRequestObject", "authMethod", "jwks")
      val written = UpdateNativeClientRequest.of(nativeRecord.id).toJson
      assertTrue(fixed.forall(field => !written.contains(s""""$field""""))) &&
      assertTrue(UpdateNativeClientRequest.of(nativeRecord.id).toUpdateClientRequest.authMethod.isEmpty)
    },
  )

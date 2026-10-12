package versola.central.configuration.edges

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.clients.certificates.{
  ClientCertificateIssuance,
  ClientCertificateIssuanceRepository,
  ClientCertificateIssuer,
  ClientCertificateService,
  EdgeCertificateEnrollmentRepository,
}
import versola.central.configuration.clients.{
  AuthFlow,
  AuthMethod,
  AuthorizationPreset,
  AuthorizationPresetService,
  ClientId,
  ClientKind,
  ClientTemplate,
  OAuthClientRecord,
  OAuthClientService,
  PresetId,
  ResponseType,
}
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.metadata.ServerMetadataService
import versola.central.configuration.permissions.{Permission, PermissionRecord, PermissionService}
import versola.central.configuration.resources.{ResourceEndpointId, ResourceEndpointRecord, ResourceId, ResourceRecord, ResourceService}
import versola.central.configuration.roles.{RoleId, RoleRecord, RoleService}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{
  GetAuthorizationPresetsSyncResponse,
  GetOAuthClientsSyncResponse,
  GetPermissionsSyncResponse,
  GetResourcesSyncResponse,
  GetRolesSyncResponse,
  ResourceUri,
}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.http.Observability
import versola.util.{Base64, JWT, RedirectUri, RsaKeyPair, Secret, SecureRandom, SecurityService, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.UUID

/** The edge-facing surface (#554). What matters here is who gets what: an edge is served the
  * tenants bound to it and nothing of another's, every secret is encrypted to that edge's own
  * key, and a caller that is not a registered edge -- auth included -- is turned away.
  */
object EdgeSyncControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config

  private val edgeId = EdgeId("edge-a")
  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  private def rsaKeyPair(keyId: String): RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(
      keyId = keyId,
      publicKey = pair.getPublic.asInstanceOf[RSAPublicKey],
      privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey],
    )

  private val edgeKeyPair = rsaKeyPair("edge-a-key")

  /** A key central never registered, for the token that names `edge-a` without holding its key. */
  private val strangerKeyPair = rsaKeyPair("stranger-key")

  private val edgeRecord = EdgeRecord(edgeId, edgeKeyPair.toPublicJwk, None, requireDpopNonce = true)

  /** What an edge presents: signed with its registered key and naming itself, exactly as edge's
    * `CentralSyncTokenService` mints it. */
  private def edgeToken(keyPair: RsaKeyPair = edgeKeyPair): Task[String] =
    JWT.serialize(
      JWT.Claims("edge", "edge", List("central"), Json.Obj()),
      1.minute,
      JWT.Signature.Asymmetric(JWT.Algorithm.RS256, keyPair.keyId, keyPair.privateKey),
      headers = Map("edge_id" -> edgeId.toString),
    )

  /** What auth presents: signed with central's own secret key, carrying no `edge_id`. */
  private val authToken: Task[String] =
    JWT.serialize(
      JWT.Claims("auth", "auth", List("central"), Json.Obj()),
      1.minute,
      JWT.Signature.Symmetric(config.secretKey),
    )

  private val clientSecret = Secret(Array.fill(48)(1.toByte))
  private val registeredAt = Instant.parse("2026-02-01T09:00:00Z")

  private def client(id: String, tenantId: TenantId) = OAuthClientRecord(
    id = ClientId(id),
    tenantId = tenantId,
    clientName = Map("en" -> id),
    redirectUris = Set(RedirectUri("https://example.com/callback")),
    scope = Set(ScopeToken("read")),
    secret = Some(clientSecret),
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set(Permission("users:read")),
    theme = "",
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
    template = Some(ClientTemplate(ClientKind.web)),
    createdAt = registeredAt,
  )

  private val clientA = client("web-a", tenantA)
  private val clientB = client("web-b", tenantB)

  private def preset(id: String, clientId: ClientId) = AuthorizationPreset(
    id = PresetId(id),
    clientId = clientId,
    description = id,
    redirectUri = RedirectUri("https://example.com/callback"),
    postLoginRedirectUri = RedirectUri("https://example.com/home"),
    postLogoutRedirectUri = None,
    scope = Set(ScopeToken("openid")),
    responseType = ResponseType.Code,
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  private val presetA = preset("login-a", clientA.id)
  private val presetB = preset("login-b", clientB.id)

  private val resourceSecret = Secret(Array.fill(32)(7.toByte))
  private val previousResourceSecret = Secret(Array.fill(32)(8.toByte))
  private val endpointId = ResourceEndpointId(UUID.fromString("018f0f2a-1c7b-7000-8000-000000000301"))

  private def resource(id: String, tenantId: TenantId) = ResourceRecord(
    tenantId = tenantId,
    resourceId = ResourceId(id),
    resource = ResourceUri(s"https://$id.example.com"),
    audience = List(clientA.id),
    endpoints = Vector(ResourceEndpointRecord(endpointId, "/users", "GET", true, None, Vector.empty, None, None, None)),
    secret = Some(resourceSecret),
    previousSecret = None,
  )

  private val resourceA = resource("api-a", tenantA)
  private val resourceB = resource("api-b", tenantB)

  private val roleA = RoleRecord(RoleId("admin"), tenantA, Map("en" -> "Admin"), Set(Permission("users:read")), active = true)
  private val roleB = RoleRecord(RoleId("admin"), tenantB, Map("en" -> "Admin"), Set(Permission("users:write")), active = true)

  private val permissionA = PermissionRecord(tenantA, Permission("users:read"), Map("en" -> "Read users"), Set(endpointId))
  private val permissionB = PermissionRecord(tenantB, Permission("users:write"), Map("en" -> "Write users"), Set.empty)

  private val jwks = Json.Obj("keys" -> Json.Arr(Json.Obj("kid" -> Json.Str("k1"), "kty" -> Json.Str("RSA"))))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** No certificate is ever issued here: the real service passes signing requests through to the
    * stubbed [[OAuthClientService]] and enrolment repository. */
  private object NoIssuances extends ClientCertificateIssuanceRepository:
    def upsert(issuance: ClientCertificateIssuance) = ZIO.unit
    def replace(issuance: ClientCertificateIssuance) = ZIO.succeed(false)
    def find(clientId: ClientId) = ZIO.none
    def delete(clientId: ClientId) = ZIO.unit
    def expiringBefore(deadline: Instant) = ZIO.succeed(Vector.empty)

  private object NoEnrollments extends EdgeCertificateEnrollmentRepository:
    def enroll(clientId: ClientId, at: Instant) = ZIO.unit
    def isEnrolled(clientId: ClientId) = ZIO.succeed(false)
    def enrolledClients = ZIO.succeed(Set.empty)
    def recordIssued(clientId: ClientId, serial: String, at: Instant, edgeId: EdgeId) = ZIO.unit
    def delete(clientId: ClientId) = ZIO.unit

  private case class Services(
      clients: Stub[OAuthClientService],
      presets: Stub[AuthorizationPresetService],
      resources: Stub[ResourceService],
      roles: Stub[RoleService],
      permissions: Stub[PermissionService],
      jwks: Stub[JwksService],
      metadata: Stub[ServerMetadataService],
      edges: Stub[EdgeService],
      security: SecurityService,
  )

  /** Every stub answers by the edge it is asked for, the way the real services scope their
    * caches: edge-a's tenants for `Some(edge-a)`, everything for anything else. A controller
    * that asked with the wrong id, or with none, would be caught by the tenant-b rows coming
    * back. */
  private def scoped(services: Services): UIO[Unit] =
    services.clients.getClientsForSync.returnsZIO {
      case Some(`edgeId`) => ZIO.succeed(Vector(clientA))
      case _ => ZIO.succeed(Vector(clientA, clientB))
    } *>
      services.presets.getPresetsForSync.returnsZIO {
        case Some(`edgeId`) => ZIO.succeed(Vector(presetA))
        case _ => ZIO.succeed(Vector(presetA, presetB))
      } *>
      services.resources.getResourcesForSync.returnsZIO {
        case Some(`edgeId`) => ZIO.succeed(Vector(resourceA))
        case _ => ZIO.succeed(Vector(resourceA, resourceB))
      } *>
      services.roles.getRolesForSync.returnsZIO {
        case Some(`edgeId`) => ZIO.succeed(Vector(roleA))
        case _ => ZIO.succeed(Vector(roleA, roleB))
      } *>
      services.permissions.getPermissionsForSync.returnsZIO {
        case Some(`edgeId`) => ZIO.succeed(Vector(permissionA))
        case _ => ZIO.succeed(Vector(permissionA, permissionB))
      } *>
      services.jwks.getRaw.succeedsWith(jwks) *>
      services.metadata.getMetadata.succeedsWith(None)

  private def testCase(
      description: String,
      request: Request,
      token: Task[String] = edgeToken(),
      expectedStatus: Status,
      setup: Services => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Services) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        httpClient <- ZIO.service[Client]
        security <- (SecureRandom.live >>> SecurityService.live).build.map(_.get[SecurityService])
        services = Services(
          clients = stub[OAuthClientService],
          presets = stub[AuthorizationPresetService],
          resources = stub[ResourceService],
          roles = stub[RoleService],
          permissions = stub[PermissionService],
          jwks = stub[JwksService],
          metadata = stub[ServerMetadataService],
          edges = stub[EdgeService],
          security = security,
        )
        certificates = ClientCertificateService.Impl(ClientCertificateIssuer.notConfigured, services.clients, NoIssuances, NoEnrollments, config)
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeSyncController.routes.provideEnvironment(
              ZEnvironment[OAuthClientService](services.clients) ++
                ZEnvironment[ClientCertificateService](certificates) ++
                ZEnvironment[AuthorizationPresetService](services.presets) ++
                ZEnvironment[ResourceService](services.resources) ++
                ZEnvironment[RoleService](services.roles) ++
                ZEnvironment[PermissionService](services.permissions) ++
                ZEnvironment[JwksService](services.jwks) ++
                ZEnvironment[ServerMetadataService](services.metadata) ++
                ZEnvironment[EdgeService](services.edges) ++
                ZEnvironment[SecurityService](security) ++
                ZEnvironment[CentralConfig](config) ++
                tracing,
            ),
          ),
        )
        _ <- services.edges.find.succeedsWith(Some(edgeRecord))
        _ <- scoped(services)
        _ <- setup(services)
        raw <- token
        response <- httpClient.batched(request.addHeader(Header.Authorization.Bearer(raw)))
        verifyResult <- verify(response, services)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def sync(resource: String): URL = URL.root / "edge" / "sync" / resource

  private val syncResources = List("clients", "resources", "roles", "permissions", "jwks", "dpop", "auth-request-presets", "server-metadata")

  private def decodeBody[A: JsonDecoder](response: Response): Task[A] =
    response.body.asString
      .flatMap(body => ZIO.fromEither(body.fromJson[A]))
      .mapError(message => RuntimeException(s"Failed to decode JSON: $message"))

  private def refusesAuthToken(resource: String) =
    testCase(
      description = s"$resource: 401, and no service is asked",
      request = Request.get(sync(resource)),
      token = authToken,
      expectedStatus = Status.Unauthorized,
      verify = (_, services) =>
        ZIO.succeed(assertTrue(
          services.clients.getClientsForSync.calls.isEmpty,
          services.resources.getResourcesForSync.calls.isEmpty,
          services.roles.getRolesForSync.calls.isEmpty,
          services.permissions.getPermissionsForSync.calls.isEmpty,
          services.presets.getPresetsForSync.calls.isEmpty,
          services.jwks.getRaw.calls.isEmpty,
          services.metadata.getMetadata.calls.isEmpty,
        )),
    )

  def spec = suite("EdgeSyncController")(
    suite("an edge is served its own tenants, and no other's")(
      testCase(
        description = "clients: edge-a's tenants only, secrets encrypted to edge-a's key",
        request = Request.get(sync("clients")),
        expectedStatus = Status.Ok,
        verify = (response, services) =>
          for
            payload <- decodeBody[GetOAuthClientsSyncResponse](response)
            wire <- ZIO.fromOption(payload.clients.headOption.flatMap(_.secret)).orElseFail(RuntimeException("no secret on the wire"))
            recovered <- services.security.decryptRsaHybrid(Base64.urlDecode(wire), edgeKeyPair.privateKey)
          yield assertTrue(
            services.clients.getClientsForSync.calls == List(Some(edgeId)),
            payload.clients.map(_.id) == Vector(clientA.id.toString),
            recovered.toVector == clientSecret.toVector,
          ),
      ),
      testCase(
        description = "resources: edge-a's tenants only, the secret encrypted to edge-a's key",
        request = Request.get(sync("resources")),
        expectedStatus = Status.Ok,
        verify = (response, services) =>
          for
            payload <- decodeBody[GetResourcesSyncResponse](response)
            wire <- ZIO.fromOption(payload.resources.headOption.flatMap(_.secret)).orElseFail(RuntimeException("no secret on the wire"))
            recovered <- services.security.decryptRsa(Base64.urlDecode(wire), edgeKeyPair.privateKey)
          yield assertTrue(
            services.resources.getResourcesForSync.calls == List(Some(edgeId)),
            payload.resources.map(_.tenantId) == Vector(tenantA),
            recovered.toVector == resourceSecret.toVector,
          ),
      ),
      // Edge stays on the previous secret until it is removed, so a rotation does not cut it
      // off from the resources it fronts.
      testCase(
        description = "resources: the previous secret stays the one served during a rotation",
        request = Request.get(sync("resources")),
        expectedStatus = Status.Ok,
        setup = services =>
          services.resources.getResourcesForSync.succeedsWith(Vector(resourceA.copy(previousSecret = Some(previousResourceSecret)))),
        verify = (response, services) =>
          for
            payload <- decodeBody[GetResourcesSyncResponse](response)
            wire <- ZIO.fromOption(payload.resources.headOption.flatMap(_.secret)).orElseFail(RuntimeException("no secret on the wire"))
            recovered <- services.security.decryptRsa(Base64.urlDecode(wire), edgeKeyPair.privateKey)
          yield assertTrue(recovered.toVector == previousResourceSecret.toVector),
      ),
      testCase(
        description = "roles: edge-a's tenants only",
        request = Request.get(sync("roles")),
        expectedStatus = Status.Ok,
        verify = (response, services) =>
          for payload <- decodeBody[GetRolesSyncResponse](response)
          yield assertTrue(
            services.roles.getRolesForSync.calls == List(Some(edgeId)),
            payload.roles.map(role => (role.tenantId, role.id, role.permissions)) == Vector((tenantA, roleA.id, roleA.permissions)),
          ),
      ),
      testCase(
        description = "permissions: edge-a's tenants only",
        request = Request.get(sync("permissions")),
        expectedStatus = Status.Ok,
        verify = (response, services) =>
          for payload <- decodeBody[GetPermissionsSyncResponse](response)
          yield assertTrue(
            services.permissions.getPermissionsForSync.calls == List(Some(edgeId)),
            payload.permissions.map(p => (p.tenantId, p.id)) == Vector((tenantA, permissionA.id)),
          ),
      ),
      testCase(
        description = "auth-request-presets: edge-a's clients only",
        request = Request.get(sync("auth-request-presets")),
        expectedStatus = Status.Ok,
        verify = (response, services) =>
          for payload <- decodeBody[GetAuthorizationPresetsSyncResponse](response)
          yield assertTrue(
            services.presets.getPresetsForSync.calls == List(Some(edgeId)),
            payload.presets.map(_.id) == Vector(presetA.id),
          ),
      ),
      testCase(
        description = "dpop: the calling edge's own policy row",
        request = Request.get(sync("dpop")),
        expectedStatus = Status.Ok,
        setup = services => services.edges.find.succeedsWith(Some(edgeRecord.copy(requireDpopNonce = false))),
        verify = (response, _) =>
          for body <- decodeBody[EdgeDpopSyncResponse](response)
          yield assertTrue(!body.requireDpopNonce),
      ),
      testCase(
        description = "jwks: the public document",
        request = Request.get(sync("jwks")),
        expectedStatus = Status.Ok,
        verify = (response, _) =>
          for body <- decodeBody[Json.Obj](response)
          yield assertTrue(body == jwks),
      ),
    ),
    suite("server-metadata is reduced to what an edge acts on")(
      testCase(
        description = "only the DPoP algorithm list is sent, whatever else the document holds",
        request = Request.get(sync("server-metadata")),
        expectedStatus = Status.Ok,
        setup = services =>
          services.metadata.getMetadata.succeedsWith(Some(Json.Obj(
            "issuer" -> Json.Str("https://auth.example.com"),
            "dpop_signing_alg_values_supported" -> Json.Arr(Json.Str("ES256")),
            "authorization_details_types_supported" -> Json.Arr(Json.Str("payment")),
          ))),
        verify = (response, _) =>
          for body <- decodeBody[Json.Obj](response)
          yield assertTrue(body == Json.Obj("dpop_signing_alg_values_supported" -> Json.Arr(Json.Str("ES256")))),
      ),
      testCase(
        description = "an empty object when no metadata was ever set",
        request = Request.get(sync("server-metadata")),
        expectedStatus = Status.Ok,
        verify = (response, _) =>
          for body <- decodeBody[Json.Obj](response)
          yield assertTrue(body == Json.Obj()),
      ),
      testCase(
        description = "an empty object when the document names no DPoP algorithms",
        request = Request.get(sync("server-metadata")),
        expectedStatus = Status.Ok,
        setup = services => services.metadata.getMetadata.succeedsWith(Some(Json.Obj("issuer" -> Json.Str("https://auth.example.com")))),
        verify = (response, _) =>
          for body <- decodeBody[Json.Obj](response)
          yield assertTrue(body == Json.Obj()),
      ),
    ),
    // The surface exists for edges alone. Auth's token names no edge and is not an edge; a
    // token naming an edge is only as good as the key it was signed with.
    suite("a caller that is not a registered edge is refused")(
      suite("auth's own token")(syncResources.map(refusesAuthToken)*),
      testCase(
        description = "a token naming edge-a but signed with a key central never registered",
        request = Request.get(sync("clients")),
        token = edgeToken(strangerKeyPair),
        expectedStatus = Status.Unauthorized,
        verify = (_, services) => ZIO.succeed(assertTrue(services.clients.getClientsForSync.calls.isEmpty)),
      ),
      testCase(
        description = "a token naming an edge that is not registered",
        request = Request.get(sync("clients")),
        expectedStatus = Status.Unauthorized,
        setup = services => services.edges.find.succeedsWith(None),
        verify = (_, services) => ZIO.succeed(assertTrue(services.clients.getClientsForSync.calls.isEmpty)),
      ),
      testCase(
        description = "an empty bearer token",
        request = Request.get(sync("clients")),
        token = ZIO.succeed(""),
        expectedStatus = Status.Unauthorized,
      ),
    ),
    suite("what only an edge may be sent travels encrypted to its key alone")(
      // Regression test for the bug encryptRsaHybrid fixes: plain RSA-OAEP transport throws
      // IllegalBlockSizeException past 190 bytes, and a real edge signing key's JWK document
      // routinely exceeds that -- the clients sync 500'd for any client that had registered
      // one, until it switched to encryptRsaHybrid.
      testCase(
        description = "an edgeSigningKey too large for a single RSA block",
        request = Request.get(sync("clients")),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clients.getClientsForSync.succeedsWith(
            Vector(clientA.copy(edgeSigningKey = Some(Secret(Array.tabulate(220)(i => (i % 251).toByte))))),
          ),
        verify = (response, services) =>
          for
            payload <- decodeBody[GetOAuthClientsSyncResponse](response)
            wire <- ZIO.fromOption(payload.clients.head.edgeSigningKey).orElseFail(RuntimeException("no key on the wire"))
            recovered <- services.security.decryptRsaHybrid(Base64.urlDecode(wire), edgeKeyPair.privateKey)
          yield assertTrue(recovered.toVector == Array.tabulate(220)(i => (i % 251).toByte).toVector),
      ),
      // The PEM of a certificate and its key is larger again than a JWK document, so this shares
      // the hybrid transport for the same reason -- and must reach an edge and nobody else.
      testCase(
        description = "the edgeClientCertificate",
        request = Request.get(sync("clients")),
        expectedStatus = Status.Ok,
        setup = services =>
          services.clients.getClientsForSync.succeedsWith(
            Vector(clientA.copy(edgeClientCertificate = Some(Secret(TestCertificates.generate().bundle.getBytes("UTF-8").nn)))),
          ),
        verify = (response, services) =>
          for
            payload <- decodeBody[GetOAuthClientsSyncResponse](response)
            wire <- ZIO.fromOption(payload.clients.head.edgeClientCertificate).orElseFail(RuntimeException("no certificate on the wire"))
            recovered <- services.security.decryptRsaHybrid(Base64.urlDecode(wire), edgeKeyPair.privateKey)
          yield assertTrue(String(recovered, "UTF-8").contains("BEGIN CERTIFICATE")),
      ),
    ),
    // #463: only an edge asks for a certificate to be signed.
    suite("edge-certificate signing")(
      testCase(
        description = "refuses a caller that is not an edge",
        request = Request.post(
          URL.root / "edge" / "clients" / "edge-certificate" / "sign",
          Body.fromString("""{"clientId":"web-a","csr":"x"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        token = authToken,
        expectedStatus = Status.Unauthorized,
      ),
      testCase(
        description = "answers an edge's request for a client that is not enrolled with 422, and signs nothing",
        request = Request.post(
          URL.root / "edge" / "clients" / "edge-certificate" / "sign",
          Body.fromString("""{"clientId":"web-a","csr":"x"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.UnprocessableEntity,
        verify = (response, _) =>
          response.body.asString.map(text => assertTrue(text.contains("not enrolled"))),
      ),
    ),
  )

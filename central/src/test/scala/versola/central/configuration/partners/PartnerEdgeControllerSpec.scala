package versola.central.configuration.partners

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.audit.{CentralAudit, CentralAuditRepository, RecordedAudit}
import versola.central.configuration.clients.{
  ApplicationType,
  AuthFlow,
  AuthMethod,
  AuthorizationPreset,
  AuthorizationPresetService,
  ClientApproval,
  ClientId,
  ClientKind,
  ClientTemplate,
  ConsentFlow,
  MutualTlsAuth,
  MutualTlsSubjectType,
  OAuthClientRecord,
  OAuthClientService,
  PresetId,
  ResponseType,
}
import versola.central.configuration.edges.{
  EdgeCertificate,
  EdgeCertificateRepository,
  EdgeEnrollmentTokenRepository,
  EdgeGroup,
  EdgeGroupId,
  EdgeGroupKind,
  EdgeId,
  EdgeRecord,
  EdgeService,
}
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.metadata.ServerMetadataService
import versola.central.configuration.resources.{ResourceId, ResourceRecord, ResourceService}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.central.configuration.{GetAuthorizationPresetsSyncResponse, GetOAuthClientsSyncResponse, GetResourcesSyncResponse, ResourceUri}
import versola.central.{CentralConfig, TestCentralConfig}
import versola.util.audit.{ActorType, AuditEvent, AuditEvents, AuditKind, AuditOutcome, AuditSeverity}
import versola.util.http.Observability
import versola.util.{JWT, RedirectUri, RsaKeyPair, Secret, SecureRandom, SecurityService, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator
import java.security.cert.Certificate
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.UUID

/** The partner-edge surface (#549, #962 §4): who gets in, and what each caller is served. A partner's edge is scoped to its
  * partner by the certificate and the token alone; a management call needs the staff member's token besides, and is
  * recorded. */
object PartnerEdgeControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config
  private val tenantId = TenantId("default")
  private val issuer = "https://id.example.com"
  private val now = Instant.parse("2026-11-10T10:00:00Z")

  private def rsaKeyPair(keyId: String): RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(keyId = keyId, publicKey = pair.getPublic.asInstanceOf[RSAPublicKey], privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey])

  private val edgeKey = rsaKeyPair("edge-a-key")
  private val authKey = rsaKeyPair("auth-key")
  private val strangerKey = rsaKeyPair("stranger-key")

  private val acmeGroup = EdgeGroupId("acme")
  private val edgeId = EdgeId("edge-acme-1")

  private def edgeRecord(groupId: EdgeGroupId, kind: EdgeGroupKind) = EdgeRecord(
    id = edgeId,
    publicKey = Some(edgeKey.toPublicJwk),
    oldPublicKey = None,
    requireDpopNonce = true,
    overloadPolicy = None,
    accessLogPolicy = None,
    rateLimitPolicies = None,
    policyMeta = None,
    groupId = groupId,
    groupKind = kind,
    publicUrl = Some("https://login.acme.example"),
  )

  private val partnerEdge = edgeRecord(acmeGroup, EdgeGroupKind.Partner)
  private val firstPartyEdge = edgeRecord(EdgeGroup.DefaultId, EdgeGroupKind.FirstParty)

  private val partnerCertificate =
    TestCertificates.generate(uris = List(EdgeCertificate.uriFor(edgeId), EdgeCertificate.groupUriFor(acmeGroup))).certificate
  private val firstPartyCertificate = TestCertificates.generate(uris = List(EdgeCertificate.uriFor(edgeId))).certificate

  private def partner(id: String, status: PartnerStatus = PartnerStatus.Active) = PartnerRecord(
    id = PartnerId(id),
    tenantId = tenantId,
    displayName = id.toUpperCase,
    logoUri = None,
    contact = PartnerContact(s"ops@$id.example", None),
    status = status,
    limits = PartnerLimits(
      scopes = Set(ScopeToken("openid")),
      redirectDomains = Set(s"$id.example"),
      maxClients = 5,
      maxEdges = 2,
      accessTokenTtlMax = 600,
      refreshTokenTtlMax = 86400,
      clientAuthMethods = Set(AuthMethod.tls_client_auth),
      rateLimitPolicy = "partner-default",
      resourceDomains = None,
    ),
    groupId = EdgeGroupId(id),
    consoleClientId = ClientId(s"$id-console"),
    settings = PartnerSettings.empty,
    reviewAt = None,
    createdAt = now,
    version = 0L,
  )

  private val acme = partner("acme")
  private val globex = partner("globex")

  private def client(id: String, owner: PartnerRecord, approval: ClientApproval = ClientApproval.Approved) = OAuthClientRecord(
    id = ClientId(id),
    tenantId = tenantId,
    clientName = Map("en" -> id),
    redirectUris = Set(RedirectUri(s"https://shop.${owner.id}.example/cb")),
    scope = Set(ScopeToken("openid")),
    secret = None,
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 3600.seconds,
    permissions = Set.empty,
    theme = "default",
    authFlow = Some(AuthFlow.default),
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = Some(ConsentFlow(allowPartial = false, rememberDuration = None, requiredScopes = Set.empty, offlineAccessOptional = false)),
    dpopBoundAccessTokens = false,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.tls_client_auth,
    mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, EdgeCertificate.groupUriFor(owner.groupId))),
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = true,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    template = Some(ClientTemplate(ClientKind.web)),
    createdAt = now,
    applicationType = ApplicationType.web,
    themeOverrides = None,
    partnerId = Some(owner.id),
    approval = approval,
  )

  private val acmeShop = client("acme-shop", acme)
  private val acmePending = client("acme-pay", acme, ClientApproval.Pending)
  private val globexShop = client("globex-shop", globex)

  private def resource(id: String, owner: Option[PartnerId]) = ResourceRecord(
    tenantId = tenantId,
    resourceId = ResourceId(id),
    resource = ResourceUri(s"https://$id.example.com"),
    audience = Nil,
    endpoints = Vector.empty,
    secret = Some(Secret(Array.fill(32)(7.toByte))),
    previousSecret = None,
    audienceRateLimits = Nil,
    partnerId = owner,
  )

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

  private def edgeToken(keyPair: RsaKeyPair = edgeKey): Task[String] =
    JWT.serialize(
      JWT.Claims("edge", "edge", List("central"), Json.Obj()),
      1.minute,
      JWT.Signature.Asymmetric(JWT.Algorithm.RS256, keyPair.keyId, keyPair.privateKey),
      headers = Map("edge_id" -> edgeId.toString),
    )

  /** The staff member's access token, as auth issues it to the console client. */
  private def staffToken(clientId: String = "acme-console", key: RsaKeyPair = authKey, iss: String = issuer): Task[String] =
    JWT.serialize(
      JWT.Claims(iss, "staff-1", List("partner-api"), Json.Obj("client_id" -> Json.Str(clientId))),
      5.minutes,
      JWT.Signature.Asymmetric(JWT.Algorithm.RS256, key.keyId, key.privateKey),
      typ = JWT.Type.AccessToken,
    )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](Tracing.live(logAnnotated = false), OpenTelemetry.contextZIO, ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")))

  private case class Services(
      edges: Stub[EdgeService],
      registry: Stub[PartnerRegistry],
      partners: Stub[PartnerService],
      partnerClients: Stub[PartnerClientService],
      clients: Stub[OAuthClientService],
      resources: Stub[ResourceService],
      presets: Stub[AuthorizationPresetService],
      jwks: Stub[JwksService],
      metadata: Stub[ServerMetadataService],
      tenants: Stub[TenantRepository],
      certificates: Stub[EdgeCertificateRepository],
      tokens: Stub[EdgeEnrollmentTokenRepository],
      auditRepository: Stub[CentralAuditRepository],
      audit: RecordedAudit,
  )

  /** Two partners, each with a client, a resource and a preset: the caller must be served acme's and never globex's. */
  private def scoped(services: Services): UIO[Unit] =
    services.registry.findByGroup.returnsZIO(group => ZIO.succeed(List(acme, globex).find(_.groupId == group))) *>
      services.registry.find.returnsZIO(id => ZIO.succeed(List(acme, globex).find(_.id == id))) *>
      services.clients.getPartnerClients.returnsZIO(id => ZIO.succeed(Vector(acmeShop, acmePending, globexShop).filter(_.partnerId.contains(id)))) *>
      services.resources.getResourcesForSync.succeedsWith(Vector(
        resource("acme-api", Some(acme.id)),
        resource("globex-api", Some(globex.id)),
        resource("own", None),
      )) *>
      services.presets.getPresetsForSync.succeedsWith(Vector(
        preset("acme-shop", acmeShop.id),
        preset("globex-shop", globexShop.id),
        preset("acme-pay", acmePending.id),
      )) *>
      services.jwks.getRaw.succeedsWith(Json.Obj("keys" -> Json.Arr())) *>
      services.jwks.getPublicKeys.succeedsWith(JWT.PublicKeys.fromJson(Json.Obj("keys" -> Json.Arr(authKey.toPublicJwk)))) *>
      services.metadata.getMetadata.succeedsWith(Some(Json.Obj("issuer" -> Json.Str(issuer)))) *>
      services.tenants.getAll.succeedsWith(Vector(TenantRecord(tenantId, "Example Bank", None))) *>
      services.partners.activate.succeedsWith(()) *>
      services.certificates.latest.succeedsWith(Map.empty)

  private def testCase(
      description: String,
      request: Request,
      edge: EdgeRecord = partnerEdge,
      certificate: Option[Certificate] = Some(partnerCertificate),
      token: Task[String] = edgeToken(),
      principal: Option[Task[String]] = None,
      expectedStatus: Status,
      setup: Services => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Services) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        security <- (SecureRandom.live >>> SecurityService.live).build.map(_.get[SecurityService])
        services = Services(
          edges = stub[EdgeService],
          registry = stub[PartnerRegistry],
          partners = stub[PartnerService],
          partnerClients = stub[PartnerClientService],
          clients = stub[OAuthClientService],
          resources = stub[ResourceService],
          presets = stub[AuthorizationPresetService],
          jwks = stub[JwksService],
          metadata = stub[ServerMetadataService],
          tenants = stub[TenantRepository],
          certificates = stub[EdgeCertificateRepository],
          tokens = stub[EdgeEnrollmentTokenRepository],
          auditRepository = stub[CentralAuditRepository],
          audit = RecordedAudit.unsafe,
        )
        tracing <- tracingLayer.build
        routes = Observability.handleErrors(
          PartnerEdgeController.routes.provideEnvironment(
            ZEnvironment[EdgeService](services.edges) ++
              ZEnvironment[PartnerRegistry](services.registry) ++
              ZEnvironment[PartnerService](services.partners) ++
              ZEnvironment[PartnerClientService](services.partnerClients) ++
              ZEnvironment[OAuthClientService](services.clients) ++
              ZEnvironment[ResourceService](services.resources) ++
              ZEnvironment[AuthorizationPresetService](services.presets) ++
              ZEnvironment[JwksService](services.jwks) ++
              ZEnvironment[ServerMetadataService](services.metadata) ++
              ZEnvironment[SecurityService](security) ++
              ZEnvironment[TenantRepository](services.tenants) ++
              ZEnvironment[EdgeCertificateRepository](services.certificates) ++
              ZEnvironment[EdgeEnrollmentTokenRepository](services.tokens) ++
              ZEnvironment[CentralAuditRepository](services.auditRepository) ++
              ZEnvironment[AuditEvents](services.audit) ++
              ZEnvironment[CentralConfig](config) ++
              tracing,
          ),
        )
        _ <- services.edges.find.succeedsWith(Some(edge))
        _ <- scoped(services)
        _ <- setup(services)
        raw <- token
        staff <- ZIO.foreach(principal)(identity)
        authorized = request.addHeader(Header.Authorization.Bearer(raw)).copy(remoteCertificate = certificate)
        sent = staff.fold(authorized)(value => authorized.addHeader(Header.Custom(PartnerPrincipal.Header, value)))
        response <- routes.runZIO(sent)
        verifyResult <- verify(response, services)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    } @@ TestAspect.silentLogging

  private def partnerPath(segments: String*): URL = segments.foldLeft(URL.root / "edge" / "partner")(_ / _)

  private def decodeBody[A: JsonDecoder](response: Response): Task[A] =
    response.body.asString.flatMap(body => ZIO.fromEither(body.fromJson[A])).mapError(message => RuntimeException(s"Failed to decode JSON: $message"))

  private def jsonBody(json: String): Body = Body.fromString(json)

  /** The fields no partner may set: where it is served from, which group, who owns it, which tenant, whether it is approved, how it
    * authenticates. */
  private val forbiddenFields: List[(String, Json)] = List(
    "groupId" -> Json.Str("default"),
    "groupKind" -> Json.Str("first_party"),
    "partnerId" -> Json.Str("globex"),
    "edgeId" -> Json.Str("edge-default"),
    "tenantId" -> Json.Str("other-tenant"),
    "approval" -> Json.Str("approved"),
    "mtlsAuth" -> Json.Obj(
      "type" -> Json.Str("tls_client_auth"),
      "subjectType" -> Json.Str("san_uri"),
      "subjectValue" -> Json.Str("urn:versola:edge-group:default"),
    ),
  )

  private def withForbidden(body: String): String =
    Json.Obj(body.fromJson[Json.Obj].toOption.get.fields ++ forbiddenFields).toJson

  /** A write route: its valid body is accepted as `clean`, the body with the forbidden fields is `dirty` (400 where unknown fields
    * are refused, the clean status where they are ignored), and `check` says what the services were given. */
  private case class Crossing(
      name: String,
      method: Method,
      path: URL,
      body: String,
      clean: Status,
      dirty: Status,
      setup: Services => UIO[Unit],
      check: Services => TestResult,
  ):
    private def request(payload: String): Request =
      Request(method = method, url = path, body = Body.fromString(payload)).addHeader(Header.ContentType(MediaType.application.json))

    def cases = List(
      testCase(
        description = s"$name: the valid body is accepted",
        request = request(body),
        principal = Some(staffToken()),
        expectedStatus = clean,
        setup = setup,
        verify = (_, services) => ZIO.succeed(check(services)),
      ),
      testCase(
        description = s"$name: the same body with groupId, groupKind, partnerId, edgeId, tenantId, approval and mtlsAuth is " +
          (if dirty == Status.BadRequest then "refused (400), and nothing is written"
           else "served as the caller's own partner, and the fields change nothing"),
        request = request(withForbidden(body)),
        principal = Some(staffToken()),
        expectedStatus = dirty,
        setup = setup,
        verify = (_, services) =>
          ZIO.succeed(if dirty == Status.BadRequest then refusedCheck(services) else check(services)),
      ),
    )

    private def refusedCheck(services: Services): TestResult =
      assertTrue(
        services.partnerClients.create.calls.isEmpty,
        services.partnerClients.update.calls.isEmpty,
        services.resources.createResource.calls.isEmpty,
        services.partners.addEdge.calls.isEmpty,
      )

  private lazy val crossingRows: List[Crossing] =
    val json =
      """{"id":"acme-pay","kind":"web","clientName":{"en":"ACME Pay"},"redirectUris":["https://pay.acme.example/cb"],"scope":["openid"],"authMethod":"tls_client_auth","accessTokenTtl":300}"""
    List(
      Crossing(
        "POST /edge/partner/clients",
        Method.POST,
        partnerPath("clients"),
        json,
        Status.Accepted,
        Status.BadRequest,
        services =>
          services.partnerClients.create.succeedsWith((acmePending, waitingCreate)) *>
            services.partnerClients.find.succeedsWith(Some(PartnerClientService.View(
              acmePending,
              PartnerClientService.Approval(PartnerClientService.RequestKind.create, PartnerClientService.RequestState.pending, Nil, Some(now), None),
            ))),
        services => assertTrue(services.partnerClients.create.calls.forall(call => call._1.id == acme.id && call._1.groupId == acme.groupId)),
      ),
      Crossing(
        "PUT /edge/partner/clients",
        Method.PUT,
        partnerPath("clients"),
        """{"id":"acme-shop","accessTokenTtl":300}""",
        Status.NoContent,
        Status.BadRequest,
        services => services.partnerClients.update.succeedsWith(Right(())),
        services => assertTrue(services.partnerClients.update.calls.forall(_._1.id == acme.id)),
      ),
      Crossing(
        "POST /edge/partner/clients/withdraw",
        Method.POST,
        partnerPath("clients", "withdraw"),
        """{"id":"acme-pay"}""",
        Status.NoContent,
        Status.NoContent,
        services => services.partnerClients.withdraw.succeedsWith(waitingCreate),
        services => assertTrue(services.partnerClients.withdraw.calls.map(call => (call._1.id, call._2)) == List((acme.id, ClientId("acme-pay")))),
      ),
      Crossing(
        "POST /edge/partner/clients/jwks",
        Method.POST,
        partnerPath("clients", "jwks"),
        s"""{"id":"acme-shop","jwks":{"keys":[${authKey.toPublicJwk.toJson}]}}""",
        Status.NoContent,
        Status.NoContent,
        services => services.partnerClients.replaceKeys.succeedsWith(()),
        services => assertTrue(services.partnerClients.replaceKeys.calls.map(call => (call._1.id, call._2)) == List((acme.id, ClientId("acme-shop")))),
      ),
      Crossing(
        "POST /edge/partner/resources",
        Method.POST,
        partnerPath("resources"),
        """{"resourceId":"acme-api-2","resource":"https://api2.acme.example","audience":["acme-shop"],"endpoints":[],"audienceRateLimits":[]}""",
        Status.Created,
        Status.Created,
        services => services.resources.createResource.succeedsWith(Right((ResourceId("acme-api-2"), None))),
        // the tenant and the owner are the partner's, the resource is internal, whatever the body said
        services =>
          assertTrue(
            services.resources.createResource.calls.map(r => (r.tenantId, r.partnerId, r.internal)) == List((acme.tenantId, Some(acme.id), true)),
          ),
      ),
      Crossing(
        "PUT /edge/partner/resources",
        Method.PUT,
        partnerPath("resources"),
        """{"resourceId":"acme-api","audience":{"add":[],"remove":[]},"deleteEndpoints":[],"createEndpoints":[]}""",
        Status.NoContent,
        Status.NoContent,
        services => services.resources.updateResource.succeedsWith(Right(())),
        services => assertTrue(services.resources.updateResource.calls.map(_.resourceId) == List(ResourceId("acme-api"))),
      ),
      Crossing(
        "POST /edge/partner/edges",
        Method.POST,
        partnerPath("edges"),
        """{"id":"edge-acme-2","publicUrl":"https://login2.acme.example"}""",
        Status.Created,
        Status.Created,
        services =>
          services.partners.addEdge.succeedsWith(PartnerService.EnrolledEdge(partnerEdge, PartnerService.Token("T", now.plusSeconds(86400)))),
        // the group of a new edge is the caller's partner's, which is where `addEdge` takes it from: it is not given one
        services =>
          assertTrue(services.partners.addEdge.calls.map(call => (call._1.id, call._1.groupId, call._2)) == List((
            acme.id,
            acme.groupId,
            EdgeId("edge-acme-2"),
          ))),
      ),
      Crossing(
        "POST /edge/partner/edges/enrollment-tokens",
        Method.POST,
        partnerPath("edges", "enrollment-tokens"),
        """{"id":"edge-acme-1"}""",
        Status.Created,
        Status.Created,
        services => services.partners.enrollmentToken.succeedsWith(PartnerService.Token("T", now.plusSeconds(86400))),
        services => assertTrue(services.partners.enrollmentToken.calls.map(call => (call._1.id, call._2)) == List((acme.id, EdgeId("edge-acme-1")))),
      ),
      Crossing(
        "DELETE /edge/partner/edges/enrollment-tokens",
        Method.DELETE,
        partnerPath("edges", "enrollment-tokens"),
        """{"id":"edge-acme-1"}""",
        Status.NoContent,
        Status.NoContent,
        services => services.partners.edges.succeedsWith(Vector(partnerEdge)) *> services.tokens.revoke.succeedsWith(0),
        services => assertTrue(services.tokens.revoke.calls == List(edgeId)),
      ),
      Crossing(
        "PUT /edge/partner/profile",
        Method.PUT,
        partnerPath("profile"),
        """{"displayName":"ACME Ltd"}""",
        Status.Accepted,
        Status.Accepted,
        services => services.partners.requestProfileChange.succeedsWith(waitingCreate),
        services => assertTrue(services.partners.requestProfileChange.calls.map(call => (call._1.id, call._3)) == List((acme.id, "ACME Ltd"))),
      ),
      Crossing(
        "PUT /edge/partner/settings",
        Method.PUT,
        partnerPath("settings"),
        """{"contact":{"email":"ops@acme.example"},"defaults":{"backChannelLogoutUri":null,"accessTokenTtl":null,"refreshTokenTtl":null}}""",
        Status.NoContent,
        Status.NoContent,
        services => services.partners.setSettings.succeedsWith(()),
        services => assertTrue(services.partners.setSettings.calls.map(_._1.id) == List(acme.id)),
      ),
    )

  def spec = suite("PartnerEdgeController")(
    suite("who gets in")(
      // #962: a partner's edge is registered without a signing key and brings the public half when it enrols, so what it signs is
      // verified against nothing until then: not the token it signs with the key it will register, nor any other.
      testCase(
        description = "an edge that has not enrolled has no key: a token it signs is refused on every route, with the right certificate",
        request = Request.get(partnerPath("me")),
        edge = partnerEdge.copy(publicKey = None),
        certificate = Some(partnerCertificate),
        expectedStatus = Status.Unauthorized,
        verify = (_, services) => ZIO.succeed(assertTrue(services.partners.activate.calls.isEmpty)),
      ),
      testCase(
        description = "an edge that has not enrolled is refused the partner's data as well",
        request = Request.get(partnerPath("sync", "clients")),
        edge = partnerEdge.copy(publicKey = None),
        certificate = Some(partnerCertificate),
        expectedStatus = Status.Unauthorized,
      ),
      testCase(
        description = "an edge that has not enrolled cannot manage anything either",
        request = Request.get(partnerPath("clients")),
        edge = partnerEdge.copy(publicKey = None),
        certificate = Some(partnerCertificate),
        expectedStatus = Status.Unauthorized,
      ),
      testCase(
        description = "a first-party edge is refused: its surface is /edge/sync",
        request = Request.get(partnerPath("me")),
        edge = firstPartyEdge,
        certificate = Some(firstPartyCertificate),
        expectedStatus = Status.Unauthorized,
        verify = (_, services) => ZIO.succeed(assertTrue(services.partners.activate.calls.isEmpty)),
      ),
      testCase(
        description = "a partner-kind edge whose group no partner points at is refused",
        request = Request.get(partnerPath("me")),
        edge = partnerEdge.copy(groupId = EdgeGroupId("orphan")),
        certificate = Some(TestCertificates.generate(uris =
          List(EdgeCertificate.uriFor(edgeId), EdgeCertificate.groupUriFor(EdgeGroupId("orphan"))),
        ).certificate),
        expectedStatus = Status.Unauthorized,
      ),
      testCase(
        description = "a suspended partner's edge is refused everything with 403: the kill switch",
        request = Request.get(partnerPath("sync", "clients")),
        expectedStatus = Status.Forbidden,
        setup = services => services.registry.findByGroup.succeedsWith(Some(acme.copy(status = PartnerStatus.Suspended))),
        verify = (_, services) => ZIO.succeed(assertTrue(services.clients.getPartnerClients.calls.isEmpty)),
      ),
      testCase(
        description = "a token signed with a key central never registered is refused before the partner is looked up",
        request = Request.get(partnerPath("me")),
        token = edgeToken(strangerKey),
        expectedStatus = Status.Unauthorized,
        verify = (_, services) => ZIO.succeed(assertTrue(services.registry.findByGroup.calls.isEmpty)),
      ),
      testCase(
        description = "no certificate in the handshake is refused",
        request = Request.get(partnerPath("me")),
        certificate = None,
        expectedStatus = Status.Unauthorized,
      ),
    ),
    suite("/me")(
      testCase(
        description = "the first call of an onboarding partner's edge activates it and tells the edge who it is",
        request = Request.get(partnerPath("me")),
        expectedStatus = Status.Ok,
        setup = services => services.registry.findByGroup.succeedsWith(Some(acme.copy(status = PartnerStatus.Onboarding))),
        verify = (response, services) =>
          for body <- decodeBody[Json.Obj](response)
          yield assertTrue(
            services.partners.activate.calls.map(_.id) == List(acme.id),
            body.get("partner").flatMap(_.asObject).flatMap(_.get("id")) == Some(Json.Str("acme")),
            body.get("console") == Some(Json.Obj("clientId" -> Json.Str("acme-console"), "presetId" -> Json.Str("acme-console"))),
            body.get("edge").flatMap(_.asObject).flatMap(_.get("publicUrl")) == Some(Json.Str("https://login.acme.example")),
            body.get("operator").flatMap(_.asObject).flatMap(_.get("issuer")) == Some(Json.Str(issuer)),
            body.get("operator").flatMap(_.asObject).flatMap(_.get("name")) == Some(Json.Str("Example Bank")),
          ),
      ),
    ),
    suite("the sync is the partner's own rows, and nothing of another partner's or the operator's")(
      testCase(
        description = "clients: acme's approved clients, without secrets or edge credentials",
        request = Request.get(partnerPath("sync", "clients")),
        expectedStatus = Status.Ok,
        verify = (response, _) =>
          for
            text <- response.body.asString
            payload <- ZIO.fromEither(text.fromJson[GetOAuthClientsSyncResponse]).mapError(RuntimeException(_))
          yield assertTrue(
            payload.clients.map(_.id) == Vector("acme-shop"),
            payload.clients.forall(c =>
              c.secret.isEmpty && c.edgeSigningKey.isEmpty && c.edgeClientCertificate.isEmpty && c.edgeCertificateSubject.isEmpty,
            ),
          ),
      ),
      testCase(
        description = "resources: acme's, the secret encrypted to the calling edge's key",
        request = Request.get(partnerPath("sync", "resources")),
        expectedStatus = Status.Ok,
        verify = (response, _) =>
          for payload <- decodeBody[GetResourcesSyncResponse](response)
          yield assertTrue(payload.resources.map(_.resourceId) == Vector(ResourceId("acme-api")), payload.resources.head.secret.isDefined),
      ),
      testCase(
        description = "auth-request-presets: the presets of acme's approved clients",
        request = Request.get(partnerPath("sync", "auth-request-presets")),
        expectedStatus = Status.Ok,
        verify = (response, _) =>
          for payload <- decodeBody[GetAuthorizationPresetsSyncResponse](response)
          yield assertTrue(payload.presets.map(_.id) == Vector(PresetId("acme-shop"))),
      ),
      testCase(
        description = "dpop: the calling edge's own row",
        request = Request.get(partnerPath("sync", "dpop")),
        expectedStatus = Status.Ok,
        verify = (response, _) => decodeBody[Json.Obj](response).map(body => assertTrue(body.get("requireDpopNonce") == Some(Json.Bool(true)))),
      ),
    ),
    suite("management needs the staff member's token")(
      testCase(
        description = "no Versola-Principal is 403, and nothing is read",
        request = Request.get(partnerPath("clients")),
        expectedStatus = Status.Forbidden,
        verify = (_, services) => ZIO.succeed(assertTrue(services.partnerClients.list.calls.isEmpty)),
      ),
      testCase(
        description = "a token of another client than the partner's console is 403",
        request = Request.get(partnerPath("clients")),
        principal = Some(staffToken(clientId = "globex-console")),
        expectedStatus = Status.Forbidden,
      ),
      testCase(
        description = "a token from another issuer is 403",
        request = Request.get(partnerPath("clients")),
        principal = Some(staffToken(iss = "https://other.example.com")),
        expectedStatus = Status.Forbidden,
      ),
      testCase(
        description = "a token auth did not sign is 403",
        request = Request.get(partnerPath("clients")),
        principal = Some(staffToken(key = strangerKey)),
        expectedStatus = Status.Forbidden,
      ),
      testCase(
        description = "the console client's live token is let in; a read is not recorded",
        request = Request.get(partnerPath("clients")),
        principal = Some(staffToken()),
        expectedStatus = Status.Ok,
        setup = services => services.partnerClients.list.succeedsWith(Vector.empty),
        verify = (_, services) =>
          for drafts <- services.audit.drafts
          yield assertTrue(services.partnerClients.list.calls.map(_.id) == List(acme.id), drafts.isEmpty),
      ),
      testCase(
        description = "a change is recorded by the staff member, with the partner and the edge, and answered 202 with the request",
        request = Request.post(
          partnerPath("clients"),
          jsonBody(
            """{"id":"acme-pay","kind":"web","clientName":{"en":"ACME Pay"},"redirectUris":["https://pay.acme.example/cb"],"scope":["openid"],""" +
              """"authMethod":"tls_client_auth","accessTokenTtl":300}""",
          ),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        principal = Some(staffToken()),
        expectedStatus = Status.Accepted,
        setup = services =>
          services.partnerClients.create.succeedsWith((acmePending, waitingCreate)) *>
            services.partnerClients.find.succeedsWith(Some(PartnerClientService.View(
              acmePending,
              PartnerClientService.Approval(
                PartnerClientService.RequestKind.create,
                PartnerClientService.RequestState.pending,
                List("clientName", "logoUri"),
                Some(now),
                None,
              ),
            ))),
        verify = (response, services) =>
          for
            body <- decodeBody[Json.Obj](response)
            drafts <- services.audit.drafts
          yield assertTrue(
            services.partnerClients.create.calls.map(call => (call._1.id, call._2.id, call._3)) == List((acme.id, ClientId("acme-pay"), "staff-1")),
            body.get("approval").flatMap(_.asObject).flatMap(_.get("status")) == Some(Json.Str("pending")),
            body.get("loginPath") == Some(Json.Str("/login/acme-pay")),
            drafts.map(_.kind) == Vector(CentralAudit.admin("partner-client", "create")),
            drafts.head.actor == Some((CentralAudit.Actors.PartnerStaff, Some("staff-1"))),
            drafts.head.outcome == AuditOutcome.success,
            drafts.head.target == Some(("partner-client", "acme-pay")),
            drafts.head.details.toJson.get("partnerId") == Some(Json.Str("acme")),
            drafts.head.details.toJson.get("edgeId") == Some(Json.Str("edge-acme-1")),
          ),
      ),
      testCase(
        description = "a refused change is recorded as a failure with its reason",
        request = Request.delete(partnerPath("clients").addQueryParam("id", "globex-shop")),
        principal = Some(staffToken()),
        expectedStatus = Status.NotFound,
        setup = services => services.partnerClients.delete.failsWith(PartnerError.NotFound("client 'globex-shop'")),
        verify = (_, services) =>
          for drafts <- services.audit.drafts
          yield assertTrue(drafts.map(_.outcome) == Vector(AuditOutcome.failure), drafts.head.reason == Some("partner_not_found")),
      ),
    ),
    suite("the audit feed")(
      testCase(
        description = "the operator's events about the partner show that the operator acted, not who; nobody's node or trace is shown",
        request = Request.get(partnerPath("audit")),
        principal = Some(staffToken()),
        expectedStatus = Status.Ok,
        setup = services => services.auditRepository.partnerEvents.succeedsWith(List(operatorEvent, staffEvent)),
        verify = (response, services) =>
          for body <- decodeBody[PartnerAuditResponse](response)
          yield assertTrue(
            services.auditRepository.partnerEvents.calls.map(_._1) == List(acme.id.toString),
            body.events.size == 2,
            body.events.head.actorType == ActorType("operator"),
            body.events.head.actorId.isEmpty,
            body.events.head.userId.isEmpty,
            body.events.head.ip.isEmpty,
            body.events.head.userAgent.isEmpty,
            body.events.head.details == Json.Obj("partnerId" -> Json.Str("acme"), "changed" -> Json.Arr()),
            body.events.head.kind == operatorEvent.kind,
            body.events.head.targetId == operatorEvent.targetId,
            // the partner's own staff are named, with the address the call came from
            body.events(1).actorType == CentralAudit.Actors.PartnerStaff,
            body.events(1).actorId == Some("staff-1"),
            body.events(1).ip == Some("203.0.113.9"),
            body.events.forall(_.nodeId == "central"),
            body.events.forall(_.traceId.isEmpty),
          ),
      ),
    ),
    suite("what a partner may register")(
      testCase(
        description = "a resource under another host than the partner's own is 400, and nothing is created",
        request = Request.post(
          partnerPath("resources"),
          jsonBody("""{"resourceId":"acme-evil","resource":"https://operator.example","audience":[],"endpoints":[],"audienceRateLimits":[]}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        principal = Some(staffToken()),
        expectedStatus = Status.BadRequest,
        verify = (_, services) => ZIO.succeed(assertTrue(services.resources.createResource.calls.isEmpty)),
      ),
      testCase(
        description = "a resource in another spelling than the one form, an address or http is 400 too",
        request = Request.post(
          partnerPath("resources"),
          jsonBody("""{"resourceId":"acme-evil","resource":"https://API.acme.example","audience":[],"endpoints":[],"audienceRateLimits":[]}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        principal = Some(staffToken()),
        expectedStatus = Status.BadRequest,
        verify = (_, services) => ZIO.succeed(assertTrue(services.resources.createResource.calls.isEmpty)),
      ),
      testCase(
        description = "changing a resource to a host that is not the partner's is 400, and to one another resource has is 409",
        request = Request.put(
          partnerPath("resources"),
          jsonBody(
            """{"resourceId":"acme-api","resource":"https://elsewhere.example","audience":{"add":[],"remove":[]},"deleteEndpoints":[],"createEndpoints":[]}""",
          ),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        principal = Some(staffToken()),
        expectedStatus = Status.BadRequest,
        verify = (_, services) => ZIO.succeed(assertTrue(services.resources.updateResource.calls.isEmpty)),
      ),
      testCase(
        description = "a new resource that the tenant already has is 409, not a 500",
        request = Request.put(
          partnerPath("resources"),
          jsonBody(
            """{"resourceId":"acme-api","resource":"https://other.acme.example","audience":{"add":[],"remove":[]},"deleteEndpoints":[],"createEndpoints":[]}""",
          ),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        principal = Some(staffToken()),
        expectedStatus = Status.Conflict,
        setup = services => services.resources.updateResource.failsWith(java.sql.SQLException("duplicate key", "23505")),
      ),
      testCase(
        description = "an edge whose certificate names only the edge, not its group, is 401",
        request = Request.get(partnerPath("me")),
        certificate = Some(TestCertificates.generate(uris = List(EdgeCertificate.uriFor(edgeId))).certificate),
        expectedStatus = Status.Unauthorized,
        verify = (_, services) => ZIO.succeed(assertTrue(services.partners.activate.calls.isEmpty)),
      ),
    ),
    // Owner requirement (#962): a partner has no way to choose first-party anywhere. Every write route of the partner's console is
    // sent its valid body, which is accepted, and the same body with every field a partner must not be able to set; the second is
    // refused (400) or the extra fields are ignored, and what the service is given is the caller's own partner and group.
    suite("a partner cannot choose first-party")(crossingRows.flatMap(row => row.cases)*),
  ) @@ TestAspect.sequential

  private def auditEvent(actorType: ActorType, actorId: Option[String], ip: Option[String]) = AuditEvent(
    id = UUID.fromString("018f0f2a-1c7b-7000-8000-0000000004a1"),
    at = now,
    tenantId = Some("tenant-a"),
    source = CentralAudit.Source,
    nodeId = "central-node-7",
    kind = AuditKind("admin.partner.suspend"),
    outcome = AuditOutcome.success,
    reason = None,
    severity = AuditSeverity.info,
    actorType = actorType,
    actorId = actorId,
    userId = Some(UUID.fromString("018f0f2a-1c7b-7000-8000-0000000004b2")),
    clientId = Some("central-admin"),
    sessionId = Some("sid-1"),
    ip = ip,
    userAgent = Some("Mozilla/5.0"),
    traceId = Some("trace-9"),
    targetType = Some("partner"),
    targetId = Some("acme"),
    details = Json.Obj("partnerId" -> Json.Str("acme"), "changed" -> Json.Arr(), "internalNote" -> Json.Str("operator only")),
  )

  private val operatorEvent = auditEvent(CentralAudit.Actors.Admin, Some("admin-user-id"), Some("198.51.100.4"))
  private val staffEvent = auditEvent(CentralAudit.Actors.PartnerStaff, Some("staff-1"), Some("203.0.113.9"))

  private val waitingCreate = PartnerApprovalRecord(
    id = ApprovalId("c"),
    partnerId = acme.id,
    kind = ApprovalKind.ClientCreate,
    targetId = "acme-pay",
    requested = Json.Obj(),
    current = None,
    applied = None,
    outsideLimits = List("clientName", "logoUri"),
    requestedBy = "staff-1",
    requestedAt = now,
    status = ApprovalStatus.Waiting,
    decidedBy = None,
    decidedAt = None,
    note = None,
  )

package versola.central.configuration.partners

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.clients.{AuthMethod, ClientId}
import versola.central.configuration.edges.{EdgeCertificateRepository, EdgeGroupId, EdgeGroupKind, EdgeId, EdgeRecord}
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.RsaKeyPair
import versola.util.http.Observability
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

/** The operator's partner routes (#962 §3): the admin credential, query parameters, the service's refusals as statuses. */
object PartnerControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val now = Instant.parse("2026-11-10T10:00:00Z")
  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  private val limits = PartnerLimits(
    scopes = Set(ScopeToken("openid")),
    redirectDomains = Set("acme.example"),
    maxClients = 3,
    maxEdges = 1,
    accessTokenTtlMax = 600,
    refreshTokenTtlMax = 86400,
    clientAuthMethods = Set(AuthMethod.tls_client_auth),
    rateLimitPolicy = "partner-default",
    resourceDomains = None,
  )

  private def partner(id: String, tenantId: TenantId) = PartnerRecord(
    id = PartnerId(id),
    tenantId = tenantId,
    displayName = id,
    logoUri = None,
    contact = PartnerContact(s"ops@$id.example", None),
    status = PartnerStatus.Active,
    limits = limits,
    groupId = EdgeGroupId(id),
    consoleClientId = ClientId(s"$id-console"),
    settings = PartnerSettings.empty,
    reviewAt = None,
    createdAt = now,
    version = 0L,
  )

  private val acme = partner("acme", tenantA)
  private val globex = partner("globex", tenantB)

  private val keyPair: RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(keyId = "edge-key", publicKey = pair.getPublic.asInstanceOf[RSAPublicKey], privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey])

  private val edge = EdgeRecord(
    id = EdgeId("edge-acme-1"),
    publicKey = Some(keyPair.toPublicJwk),
    oldPublicKey = None,
    requireDpopNonce = true,
    overloadPolicy = None,
    accessLogPolicy = None,
    rateLimitPolicies = None,
    policyMeta = None,
    groupId = acme.groupId,
    groupKind = EdgeGroupKind.Partner,
    publicUrl = Some("https://login.acme.example"),
  )

  private val waiting = PartnerApprovalRecord(
    id = ApprovalId("c"),
    partnerId = acme.id,
    kind = ApprovalKind.ClientCreate,
    targetId = "acme-shop",
    requested = Json.Obj("id" -> Json.Str("acme-shop")),
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

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](Tracing.live(logAnnotated = false), OpenTelemetry.contextZIO, ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")))

  private case class Stubs(service: Stub[PartnerService], registry: Stub[PartnerRegistry])

  private def testCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      authorized: Boolean = true,
      setup: Stubs => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stubs) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      val stubs = Stubs(stub[PartnerService], stub[PartnerRegistry])
      val resources = stub[ResourceService]
      val certificates = stub[EdgeCertificateRepository]
      for
        client <- ZIO.service[Client]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            PartnerController.routes.provideEnvironment(
              ZEnvironment[PartnerService](stubs.service) ++
                ZEnvironment[PartnerRegistry](stubs.registry) ++
                ZEnvironment[ResourceService](resources) ++
                ZEnvironment[EdgeCertificateRepository](certificates) ++
                ZEnvironment[CentralConfig](TestCentralConfig.config) ++
                tracing,
            ),
          ),
        )
        _ <- resources.verifySecret.succeedsWith(true)
        _ <- certificates.latest.succeedsWith(Map.empty)
        _ <- stubs.registry.getAll.succeedsWith(Vector(acme, globex))
        _ <- setup(stubs)
        authed = if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request
        response <- client.batched(authed)
        verifyResult <- verify(response, stubs)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def json(body: String): Body = Body.fromString(body)

  private val createBody =
    """{"partner":{"id":"acme","tenantId":"tenant-a","displayName":"ACME","contact":{"email":"ops@acme.example"},""" +
      """"limits":{"scopes":["openid"],"redirectDomains":["acme.example"],"maxClients":3,"maxEdges":1,"accessTokenTtlMax":600,""" +
      """"refreshTokenTtlMax":86400,"clientAuthMethods":["tls_client_auth"],"rateLimitPolicy":"partner-default"}},""" +
      """"consoleClient":{"redirectUri":"https://console.acme.example/complete"},"firstEdge":{"id":"edge-acme-1","publicUrl":"https://login.acme.example"}}"""

  def spec = suite("PartnerController")(
    testCase(
      description = "GET /configuration/partners lists every partner, or a tenant's",
      request = Request.get((URL.root / "configuration" / "partners").addQueryParam("tenantId", "tenant-b")),
      expectedStatus = Status.Ok,
      verify = (response, _) =>
        response.body.asJson[GetPartnersResponse].map(body => assertTrue(body.partners.map(_.id) == Vector(globex.id))),
    ),
    testCase(
      description = "GET /configuration/partners needs the admin credential",
      request = Request.get(URL.root / "configuration" / "partners"),
      authorized = false,
      expectedStatus = Status.Unauthorized,
    ),
    testCase(
      description = "POST /configuration/partners creates everything in one request and answers the enrollment token once",
      request = Request.post(URL.root / "configuration" / "partners", json(createBody)).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = stubs =>
        stubs.service.create.succeedsWith(PartnerService.Created(
          acme.copy(status = PartnerStatus.Onboarding),
          PartnerService.EnrolledEdge(edge, PartnerService.Token("TOKEN", now.plusSeconds(86400))),
        )),
      verify = (response, stubs) =>
        for body <- response.body.asJson[CreatedPartnerResponse]
        yield assertTrue(
          stubs.service.create.calls.map(c => (c.id, c.tenantId, c.consoleRedirectUri: String, c.firstEdgeId, c.firstEdgePublicUrl)) ==
            List((PartnerId("acme"), tenantA, "https://console.acme.example/complete", EdgeId("edge-acme-1"), "https://login.acme.example")),
          body.partner.status == PartnerStatus.Onboarding,
          body.edge.id == EdgeId("edge-acme-1"),
          body.edge.partnerId == Some(PartnerId("acme")),
          body.enrollmentToken == "TOKEN",
          body.edge.id == EdgeId("edge-acme-1"),
        ),
    ),
    testCase(
      description = "a refusal of the service is the status it means: 409 for a taken id",
      request = Request.post(URL.root / "configuration" / "partners", json(createBody)).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Conflict,
      setup = stubs => stubs.service.create.failsWith(PartnerError.Conflict("partner 'acme' already exists")),
      verify = (response, _) => response.body.asString.map(text => assertTrue(text.contains("already exists"))),
    ),
    testCase(
      description = "PUT /configuration/partners changes the profile; an unknown partner is 404",
      request = Request.put(
        URL.root / "configuration" / "partners",
        json("""{"id":"nobody","displayName":"X","contact":{"email":"x@example.com"}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NotFound,
      setup = stubs => stubs.service.updateProfile.failsWith(PartnerError.NotFound("partner 'nobody'")),
    ),
    testCase(
      description = "POST /configuration/partners/suspend and /resume are the kill switch",
      request = Request.post(
        URL.root / "configuration" / "partners" / "suspend",
        json("""{"id":"acme","note":"fraud"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = stubs => stubs.service.suspend.succeedsWith(PartnerService.SuspendOutcome(authSynced = true)),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.service.suspend.calls == List(acme.id))),
    ),
    testCase(
      description = "a suspension auth could not be told of is 207, and the partner is suspended all the same",
      request = Request.post(
        URL.root / "configuration" / "partners" / "suspend",
        json("""{"id":"acme"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.MultiStatus,
      setup = stubs => stubs.service.suspend.succeedsWith(PartnerService.SuspendOutcome(authSynced = false)),
    ),
    testCase(
      description = "resuming a partner that is not suspended is 409",
      request = Request.post(
        URL.root / "configuration" / "partners" / "resume",
        json("""{"id":"acme"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Conflict,
      setup = stubs => stubs.service.resume.failsWith(PartnerError.Conflict("partner 'acme' is not suspended")),
    ),
    testCase(
      description = "PUT /configuration/partners/limits answers the clients now outside the limits",
      request = Request.put(
        URL.root / "configuration" / "partners" / "limits",
        json(
          """{"id":"acme","limits":{"scopes":["openid"],"redirectDomains":["acme.example"],"maxClients":3,"maxEdges":1,"accessTokenTtlMax":300,""" +
            """"refreshTokenTtlMax":86400,"clientAuthMethods":["tls_client_auth"],"rateLimitPolicy":"partner-default"}}""",
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Ok,
      setup =
        stubs => stubs.service.setLimits.succeedsWith(Vector(PartnerService.ClientOutsideLimits(ClientId("acme-shop"), List("accessTokenTtl")))),
      verify = (response, stubs) =>
        for body <- response.body.asJson[SetPartnerLimitsResponse]
        yield assertTrue(
          stubs.service.setLimits.calls.map(_._2.accessTokenTtlMax) == List(300L),
          body.outsideLimits == Vector(ClientOutsideLimitsResponse(ClientId("acme-shop"), List("accessTokenTtl"))),
        ),
    ),
    testCase(
      description = "bad limits are 400 with the reason",
      request = Request.put(
        URL.root / "configuration" / "partners" / "limits",
        json(
          """{"id":"acme","limits":{"scopes":[],"redirectDomains":[],"maxClients":0,"maxEdges":0,"accessTokenTtlMax":1,"refreshTokenTtlMax":1,"clientAuthMethods":[],"rateLimitPolicy":"x"}}""",
        ),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
      setup = stubs =>
        stubs.service.setLimits.failsWith(
          PartnerError.Invalid("limits.clientAuthMethods must be a non-empty subset of tls_client_auth, private_key_jwt"),
        ),
      verify = (response, _) => response.body.asString.map(text => assertTrue(text.contains("clientAuthMethods"))),
    ),
    testCase(
      description = "GET /configuration/partners/approvals passes the filters on",
      request = Request.get(
        (URL.root / "configuration" / "partners" / "approvals").addQueryParam(
          "tenantId",
          "tenant-a",
        ).addQueryParam("status", "waiting").addQueryParam("partnerId", "acme"),
      ),
      expectedStatus = Status.Ok,
      setup = stubs => stubs.service.approvals.succeedsWith(Vector(waiting)),
      verify = (response, stubs) =>
        for body <- response.body.asString.flatMap(text => ZIO.fromEither(text.fromJson[GetPartnerApprovalsResponse]).mapError(RuntimeException(_)))
        yield assertTrue(
          stubs.service.approvals.calls == List((
            Some(tenantA),
            Some(ApprovalStatus.Waiting),
            Some(acme.id),
            PartnerController.DefaultApprovalsLimit,
          )),
          body.approvals.map(_.id) == Vector(ApprovalId("c")),
        ),
    ),
    testCase(
      description = "POST /configuration/partners/approvals/approve takes the id, the note and the overrides in the body",
      request = Request.post(
        URL.root / "configuration" / "partners" / "approvals" / "approve",
        json("""{"id":"c","note":"ok","overrides":{"clientName":{"en":"ACME Shop (verified)"}}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Ok,
      setup = stubs => stubs.service.approve.succeedsWith(waiting.copy(status = ApprovalStatus.Approved)),
      verify = (response, stubs) =>
        for body <- response.body.asString.flatMap(text => ZIO.fromEither(text.fromJson[PartnerApprovalResponse]).mapError(RuntimeException(_)))
        yield assertTrue(
          stubs.service.approve.calls.map(c => (c._1, c._3, c._4)) ==
            List((ApprovalId("c"), Some("ok"), Some(Json.Obj("clientName" -> Json.Obj("en" -> Json.Str("ACME Shop (verified)")))))),
          body.status == ApprovalStatus.Approved,
        ),
    ),
    testCase(
      description = "POST /configuration/partners/approvals/reject needs a note; an already decided request is 409",
      request = Request.post(URL.root / "configuration" / "partners" / "approvals" / "reject", json("""{"id":"c","note":"no"}"""))
        .addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Conflict,
      setup = stubs => stubs.service.reject.failsWith(PartnerError.Conflict("approval 'c' is approved")),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.service.reject.calls.map(c => (c._1, c._3)) == List((ApprovalId("c"), "no")))),
    ),
    testCase(
      description = "a reject without a note is a bad request",
      request = Request.post(
        URL.root / "configuration" / "partners" / "approvals" / "reject",
        json("""{"id":"c"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
    ),
  )

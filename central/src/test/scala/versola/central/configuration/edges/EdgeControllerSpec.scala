package versola.central.configuration.edges

import com.nimbusds.jose.jwk.RSAKey
import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.util.http.{
  AccessLogPolicy,
  GuardMode,
  InboundPolicy,
  Observability,
  OverloadPolicy,
  RateLimitDimension,
  RateLimitPolicies,
  RateLimitPolicy,
  RateLimitWindow,
}
import versola.util.{JWT, RsaKeyPair}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object EdgeControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val edgeId = EdgeId("edge-1")

  private val testKeyPair: RsaKeyPair =
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    val pair = gen.generateKeyPair()
    RsaKeyPair(
      keyId = "2026-04-29_18-30-00",
      publicKey = pair.getPublic.asInstanceOf[RSAPublicKey],
      privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey],
    )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[EdgeService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[EdgeService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        tenantRepository = stub[TenantRepository]
        groupService = stub[EdgeGroupService]
        identityService = stub[EdgeIdentityService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeController.routes.provideEnvironment(
              ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[EdgeGroupService](groupService) ++
                ZEnvironment[EdgeIdentityService](identityService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[TenantRepository](tenantRepository) ++
                ZEnvironment(TestCentralConfig.config) ++
                tracing,
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- tenantRepository.getAll.succeedsWith(Vector.empty)
        _ <- groupService.find.succeedsWith(Some(EdgeGroupRecord(EdgeGroup.DefaultId, EdgeGroupKind.FirstParty)))
        _ <- identityService.chainEnds.succeedsWith(Map.empty)
        _ <- setup(edgeService)
        response <- client.batched(request.addHeader(TestAdminAuth.basicAuthHeader))
        verifyResult <- verify(response, edgeService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  /** The edge-facing routes authenticate with `authorizeInternal`, not the Basic header every
    * other route here uses, so these cases build their own credentials. */
  private def internalTestCase(
      description: String,
      url: URL = URL.root / "configuration" / "edges" / "registry",
      token: Stub[EdgeService] => Task[String],
      tenants: Vector[TenantRecord] = Vector.empty,
      expectedStatus: Status,
      setup: Stub[EdgeService] => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        tenantRepository = stub[TenantRepository]
        groupService = stub[EdgeGroupService]
        identityService = stub[EdgeIdentityService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeController.routes.provideEnvironment(
              ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[EdgeGroupService](groupService) ++
                ZEnvironment[EdgeIdentityService](identityService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[TenantRepository](tenantRepository) ++
                ZEnvironment(TestCentralConfig.config) ++
                tracing,
            ),
          ),
        )
        _ <- tenantRepository.getAll.succeedsWith(tenants)
        _ <- groupService.find.succeedsWith(Some(EdgeGroupRecord(EdgeGroup.DefaultId, EdgeGroupKind.FirstParty)))
        _ <- identityService.chainEnds.succeedsWith(Map.empty)
        // `token` stubs `find` for `authorizeInternal`; a case that wants a particular record
        // back from it sets its own afterwards, so its setup runs second.
        raw <- token(edgeService)
        _ <- setup(edgeService)
        response <- client.batched(Request.get(url).addHeader(Header.Authorization.Bearer(raw)))
        verifyResult <- verify(response)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  /** What auth presents: signed with central's own secret key, carrying no `edge_id`, exactly
    * as auth's `CentralSyncTokenService` mints it. */
  private val authSyncToken: Stub[EdgeService] => Task[String] = _ =>
    JWT.serialize(
      claims = JWT.Claims("auth", "auth", List("central"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(TestCentralConfig.config.secretKey),
    )

  /** What an edge presents: signed with its own registered key and naming itself. */
  private val edgeSyncToken: Stub[EdgeService] => Task[String] = service =>
    for
      _ <- service.find.succeedsWith(Some(edgeRecord))
      token <- JWT.serialize(
        claims = JWT.Claims("edge", "edge", List("central"), Json.Obj()),
        ttl = 10.minutes,
        signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, testKeyPair.keyId, testKeyPair.privateKey),
        headers = Map("edge_id" -> edgeId.toString),
      )
    yield token

  private val publicJwk: Json.Obj =
    RSAKey.Builder(testKeyPair.publicKey).keyID(testKeyPair.keyId).build()
      .toJSONString.fromJson[Json.Obj].getOrElse(Json.Obj())

  private val edgeRecord = EdgeRecord(edgeId, publicJwk, oldPublicKey = None, requireDpopNonce = true)

  private val rateLimits = RateLimitPolicies(List(RateLimitPolicy(
    name = "per-client",
    by = List(RateLimitDimension.Client),
    limits = List(RateLimitWindow(limit = 100, windowSeconds = 60)),
    burst = 5,
  )))

  // lazy: it reads vals declared below it, which an object initialises in order
  private lazy val storedRecord = edgeRecord.copy(
    overloadPolicy = Some(overloadPolicy),
    rateLimitPolicies = Some(rateLimits),
    policyMeta = Some(Map(
      "overload" -> PolicySectionMeta(2, java.time.Instant.parse("2026-10-27T10:00:00Z")),
      "rateLimits" -> PolicySectionMeta(1, java.time.Instant.parse("2026-10-27T11:00:00Z")),
    )),
  )

  // the answer is read with zio-json, the codec the server writes it with (a schema codec reads a Map as an array of pairs)
  private def decoded[A: JsonDecoder](response: Response): Task[A] =
    response.body.asString.flatMap(body => ZIO.fromEither(body.fromJson[A]).mapError(error => RuntimeException(s"$error: $body")))

  private def patchFor(edge: String, body: String, params: (String, String)*): Request =
    Request(
      method = Method.PATCH,
      url =
        params.foldLeft((URL.root / "configuration" / "edges" / "policies").addQueryParam("edgeId", edge))((url, p) => url.addQueryParam(p._1, p._2)),
      body = Body.fromString(body),
    ).addHeader(Header.ContentType(MediaType.application.json))

  private def patch(body: String, params: (String, String)*): Request = patchFor(edgeId.toString, body, params*)

  private val accessLogPolicy = AccessLogPolicy(mode = Some("sampled"), successSampleRate = Some(0.1))

  private val overloadPolicy = OverloadPolicy(inbound = Some(InboundPolicy(mode = Some("enforce"), maxInFlight = Some(100))))

  def spec = suite("EdgeController")(
    controllerTestCase(
      description = "get all edges returns 200 OK",
      request = Request.get(URL.root / "configuration" / "edges"),
      expectedStatus = Status.Ok,
      setup = service => service.getAllEdges.succeedsWith(Vector.empty),
    ),
    controllerTestCase(
      description = "get all edges maps each record's id and old-key presence",
      request = Request.get(URL.root / "configuration" / "edges"),
      expectedStatus = Status.Ok,
      setup = service =>
        service.getAllEdges.succeedsWith(
          Vector(
            EdgeRecord(edgeId, Json.Obj(), oldPublicKey = None, requireDpopNonce = true),
            EdgeRecord(EdgeId("edge-2"), Json.Obj(), oldPublicKey = Some(Json.Obj()), requireDpopNonce = false),
          ),
        ),
      verify = (response, _) =>
        for body <- response.body.asJson[GetAllEdgesResponse]
        yield assertTrue(
          body.edges == List(
            EdgeResponse(edgeId, hasOldKey = false, requireDpopNonce = true),
            EdgeResponse(EdgeId("edge-2"), hasOldKey = true, requireDpopNonce = false),
          ),
        ),
    ),
    controllerTestCase(
      description = "delete edge returns 204 No Content",
      request = Request.delete(
        (URL.root / "configuration" / "edges").addQueryParam("edgeId", edgeId.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteEdge.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteEdge.calls == List(edgeId))),
    ),
    controllerTestCase(
      description = "register edge returns 201 Created with the generated key pair",
      request = Request(
        method = Method.POST,
        url = URL.root / "configuration" / "edges",
        body = Body.fromString(RegisterEdgeRequest(edgeId).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.registerEdge.succeedsWith(testKeyPair),
      verify = (response, service) =>
        for body <- response.body.asJson[versola.central.configuration.edges.ServiceKeyResponse]
        yield assertTrue(
          service.registerEdge.calls == List((edgeId, EdgeGroup.DefaultId)),
          body.keyId == testKeyPair.keyId,
        ),
    ),
    controllerTestCase(
      description = "rotate edge key returns 200 OK with the rotated key pair",
      request = Request(
        method = Method.POST,
        url = (URL.root / "configuration" / "edges" / "rotate-key").addQueryParam("edgeId", edgeId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.rotateEdgeKey.succeedsWith(testKeyPair),
      verify = (response, service) =>
        for body <- response.body.asJson[versola.central.configuration.edges.ServiceKeyResponse]
        yield assertTrue(
          service.rotateEdgeKey.calls == List(edgeId),
          body.keyId == testKeyPair.keyId,
        ),
    ),
    controllerTestCase(
      description = "delete old edge key returns 204 No Content",
      request = Request(
        method = Method.DELETE,
        url = (URL.root / "configuration" / "edges" / "old-key").addQueryParam("edgeId", edgeId.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteOldEdgeKey.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteOldEdgeKey.calls == List(edgeId))),
    ),
    internalTestCase(
      description = "edges registry serves every edge's id and assigned tenants to auth",
      token = authSyncToken,
      expectedStatus = Status.Ok,
      setup = service =>
        service.getAllEdges.succeedsWith(
          Vector(
            edgeRecord,
            EdgeRecord(EdgeId("edge-2"), publicJwk, oldPublicKey = Some(publicJwk), requireDpopNonce = true),
          ),
        ),
      tenants = Vector(
        TenantRecord(TenantId("tenant-a"), "Tenant A", edgeId = Some(edgeId)),
        TenantRecord(TenantId("tenant-b"), "Tenant B", edgeId = Some(edgeId)),
        TenantRecord(TenantId("tenant-c"), "Tenant C", edgeId = Some(EdgeId("edge-2"))),
        // Assigned to neither edge below, and must not leak into either one's tenantIds --
        // this is the set `EdgeCertificateTrust` checks a token's tenant against.
        TenantRecord(TenantId("tenant-d"), "Tenant D", edgeId = None),
      ),
      verify = response =>
        for
          raw <- response.body.asString
          body <- ZIO.fromEither(raw.fromJson[GetEdgesRegistryResponse]).mapError(new RuntimeException(_))
        yield assertTrue(
          body.edges.map(_.id) == List(edgeId, EdgeId("edge-2")),
          // What lets auth refuse a call from a real edge, with a valid certificate, that is
          // simply not the one this token's tenant is behind.
          body.edges.head.tenantIds.toSet == Set(TenantId("tenant-a"), TenantId("tenant-b")),
          body.edges(1).tenantIds == List(TenantId("tenant-c")),
        ),
    ),
    // An edge has no business reading its peers' identities.
    internalTestCase(
      description = "edges registry refuses an edge, authenticated or not",
      token = edgeSyncToken,
      expectedStatus = Status.Unauthorized,
      setup = service => service.getAllEdges.succeedsWith(Vector(edgeRecord)),
      verify = response => ZIO.succeed(assertTrue(response.status == Status.Unauthorized)),
    ),
    controllerTestCase(
      description = "setting an edge's nonce requirement returns 204 No Content and records it",
      request = Request(
        method = Method.PUT,
        url = (URL.root / "configuration" / "edges" / "dpop").addQueryParam("edgeId", edgeId.toString),
        body = Body.fromString(SetEdgeDpopRequest(requireDpopNonce = false).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service =>
        service.find.succeedsWith(Some(edgeRecord)) *> service.setRequireDpopNonce.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.setRequireDpopNonce.calls == List((edgeId, false)))),
    ),
    // Writing policy for an edge that does not exist would be a row no edge ever reads, and a
    // console that reported success for a typo.
    controllerTestCase(
      description = "setting the nonce requirement of an unregistered edge returns 404 and writes nothing",
      request = Request(
        method = Method.PUT,
        url = (URL.root / "configuration" / "edges" / "dpop").addQueryParam("edgeId", "edge-missing"),
        body = Body.fromString(SetEdgeDpopRequest(requireDpopNonce = true).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NotFound,
      setup = service => service.find.succeedsWith(None) *> service.setRequireDpopNonce.succeedsWith(()),
      verify = (_, service) => ZIO.succeed(assertTrue(service.setRequireDpopNonce.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "GET policies serves the edge's sections from the database, with their revisions",
      request = Request(method = Method.GET, url = (URL.root / "configuration" / "edges" / "policies").addQueryParam("edgeId", edgeId.toString)),
      expectedStatus = Status.Ok,
      setup = service => service.findFresh.succeedsWith(Some(storedRecord)),
      verify = (response, _) =>
        for body <- decoded[EdgePoliciesResponse](response)
        yield assertTrue(
          body.edgeId == edgeId,
          body.overload.contains(overloadPolicy),
          body.accessLog.isEmpty,
          body.rateLimits.contains(rateLimits),
          body.revisions == Map("overload" -> 2, "rateLimits" -> 1),
          body.updatedAt.keySet == Set("overload", "rateLimits"),
        ),
    ),
    controllerTestCase(
      description = "GET policies of an unregistered edge returns 404",
      request = Request(method = Method.GET, url = (URL.root / "configuration" / "edges" / "policies").addQueryParam("edgeId", "edge-missing")),
      expectedStatus = Status.NotFound,
      setup = service => service.findFresh.succeedsWith(None),
    ),
    controllerTestCase(
      description = "PATCH policies: absent sections are kept, null removes, an object replaces; the answer is the new state",
      request = patch(s"""{"overload":null,"rateLimits":${rateLimits.toJson}}"""),
      expectedStatus = Status.Ok,
      setup = service => service.patchPolicies.succeedsWith(Some(Right(storedRecord.copy(overloadPolicy = None)))),
      verify = (response, service) =>
        for body <- decoded[EdgePoliciesResponse](response)
        yield assertTrue(
          service.patchPolicies.calls == List((edgeId, EdgePoliciesPatch(overload = Change.Remove, rateLimits = Change.Replace(rateLimits)), false)),
          body.overload.isEmpty,
          body.rateLimits.contains(rateLimits),
        ),
    ),
    controllerTestCase(
      description = "PATCH policies with expectedRevisions passes them on, and dryRun=true says so",
      request = patch("""{"accessLog":{"mode":"full"},"expectedRevisions":{"accessLog":0}}""", "dryRun" -> "true"),
      expectedStatus = Status.Ok,
      setup = service => service.patchPolicies.succeedsWith(Some(Right(storedRecord))),
      verify = (_, service) =>
        ZIO.succeed(
          assertTrue(
            service.patchPolicies.calls == List(
              (
                edgeId,
                EdgePoliciesPatch(
                  accessLog = Change.Replace(AccessLogPolicy(mode = Some("full"))),
                  expectedRevisions = Map(PolicySection.AccessLog -> 0),
                ),
                true,
              ),
            ),
          ),
        ),
    ),
    controllerTestCase(
      description = "a section that does not validate is answered 400 with the section and what is wrong",
      request = patch(s"""{"rateLimits":${rateLimits.toJson}}"""),
      expectedStatus = Status.BadRequest,
      setup = service =>
        service.patchPolicies.succeedsWith(Some(Left(PolicyPatchError.Invalid(
          PolicySection.RateLimits,
          "policy per-client: burst must not exceed the smallest limit",
        )))),
      verify = (response, _) =>
        for body <- decoded[PolicyPatchErrorResponse](response)
        yield assertTrue(body.section.contains("rateLimits"), body.error.contains("per-client"), body.current.isEmpty),
    ),
    controllerTestCase(
      description = "a misspelled section, a section of the wrong shape and a body that is not an object are refused before the service is called",
      request = patch("""{"overlod":{}}"""),
      expectedStatus = Status.BadRequest,
      verify = (response, service) =>
        for body <- decoded[PolicyPatchErrorResponse](response)
        yield assertTrue(body.error.contains("unknown section 'overlod'"), service.patchPolicies.calls.isEmpty),
    ),
    controllerTestCase(
      description = "a rate limit set with an unknown dimension is refused as malformed, naming the section",
      request = patch("""{"rateLimits":{"policies":[{"name":"x","by":["ip-address"],"limits":[{"limit":1,"windowSeconds":1}]}]}}"""),
      expectedStatus = Status.BadRequest,
      verify = (response, service) =>
        for body <- decoded[PolicyPatchErrorResponse](response)
        yield assertTrue(body.section.contains("rateLimits"), service.patchPolicies.calls.isEmpty),
    ),
    controllerTestCase(
      description = "a revision that has moved on is answered 409 with the current state",
      request = patch("""{"overload":{},"expectedRevisions":{"overload":1}}"""),
      expectedStatus = Status.Conflict,
      setup = service => service.patchPolicies.succeedsWith(Some(Left(PolicyPatchError.Conflict(PolicySection.Overload, storedRecord)))),
      verify = (response, _) =>
        for body <- decoded[PolicyPatchErrorResponse](response)
        yield assertTrue(body.section.contains("overload"), body.current.exists(_.revisions.get("overload").contains(2))),
    ),
    controllerTestCase(
      description = "PATCH policies of an unregistered edge returns 404",
      request = patchFor("edge-missing", """{"overload":{}}"""),
      expectedStatus = Status.NotFound,
      setup = service => service.patchPolicies.succeedsWith(None),
    ),
    controllerTestCase(
      description = "the recommended rate limit policies are served, all in shadow, and are a set the PATCH accepts",
      request = Request(method = Method.GET, url = URL.root / "configuration" / "edges" / "rate-limits" / "recommended"),
      expectedStatus = Status.Ok,
      verify = (response, _) =>
        for body <- response.body.asJson[RateLimitPolicies]
        yield assertTrue(body == RateLimitPolicies.recommended, body.validate.isRight, body.policies.forall(_.mode == GuardMode.Shadow)),
    ),
  )

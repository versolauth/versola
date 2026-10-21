package versola.central.configuration.edges

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantRepository
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.util.RsaKeyPair
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

/** The admin API of edge groups and of enrollment tokens: who may create what, and that an edge cannot be put in a
  * group that does not exist. */
object EdgeGroupEndpointsSpec extends ZIOSpecDefault, ZIOStubs:

  private val edgeId = EdgeId("edge-1")
  private val partnerGroup = EdgeGroupRecord(EdgeGroupId("partner-acme"), EdgeGroupKind.Partner)

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

  private case class Stubs(
      edges: Stub[EdgeService],
      groups: Stub[EdgeGroupService],
      identity: Stub[EdgeIdentityService],
      resources: Stub[ResourceService],
  )

  private def testCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      authorized: Boolean = true,
      setup: Stubs => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stubs) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      val stubs = Stubs(stub[EdgeService], stub[EdgeGroupService], stub[EdgeIdentityService], stub[ResourceService])
      for
        client <- ZIO.service[Client]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeController.routes.provideEnvironment(
              ZEnvironment[EdgeService](stubs.edges) ++
                ZEnvironment[EdgeGroupService](stubs.groups) ++
                ZEnvironment[EdgeIdentityService](stubs.identity) ++
                ZEnvironment[ResourceService](stubs.resources) ++
                ZEnvironment[TenantRepository](stub[TenantRepository]) ++
                ZEnvironment(TestCentralConfig.config) ++
                tracing,
            ),
          ),
        )
        _ <- stubs.resources.verifySecret.succeedsWith(true)
        _ <- setup(stubs)
        authed = if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request
        response <- client.batched(authed)
        result <- verify(response, stubs)
      yield assertTrue(response.status == expectedStatus) && result
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private def json(method: Method, url: URL, body: String) =
    Request(method = method, url = url, body = Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))

  def spec = suite("edge group endpoints")(
    testCase(
      description = "a group is created with the kind the operator names",
      request = json(Method.POST, URL.root / "configuration" / "edge-groups", partnerGroup.toJson),
      expectedStatus = Status.Created,
      setup = stubs => stubs.groups.create.succeedsWith(true),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.groups.create.calls == List(partnerGroup))),
    ),
    testCase(
      description = "an existing group is 409 and is not touched",
      request = json(Method.POST, URL.root / "configuration" / "edge-groups", partnerGroup.toJson),
      expectedStatus = Status.Conflict,
      setup = stubs => stubs.groups.create.succeedsWith(false),
    ),
    testCase(
      description = "an unknown kind is refused before anything is stored",
      request = json(Method.POST, URL.root / "configuration" / "edge-groups", """{"id":"x","kind":"internal"}"""),
      expectedStatus = Status.BadRequest,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.groups.create.calls.isEmpty)),
    ),
    testCase(
      description = "creating a group needs the admin credential",
      request = json(Method.POST, URL.root / "configuration" / "edge-groups", partnerGroup.toJson),
      authorized = false,
      expectedStatus = Status.Unauthorized,
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.groups.create.calls.isEmpty)),
    ),
    testCase(
      description = "groups are listed",
      request = Request.get(URL.root / "configuration" / "edge-groups"),
      expectedStatus = Status.Ok,
      setup = stubs => stubs.groups.getAll.succeedsWith(Vector(partnerGroup)),
      verify = (response, _) =>
        for body <- response.body.asJson[GetEdgeGroupsResponse] yield assertTrue(body.groups == Vector(partnerGroup)),
    ),
    testCase(
      description = "an edge is registered in the group it names",
      request = json(Method.POST, URL.root / "configuration" / "edges", s"""{"id":"edge-1","groupId":"partner-acme"}"""),
      expectedStatus = Status.Created,
      setup = stubs => stubs.groups.find.succeedsWith(Some(partnerGroup)) *> stubs.edges.registerEdge.succeedsWith(testKeyPair),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.edges.registerEdge.calls == List((edgeId, partnerGroup.id)))),
    ),
    testCase(
      description = "an edge that names a group that does not exist is not registered",
      request = json(Method.POST, URL.root / "configuration" / "edges", s"""{"id":"edge-1","groupId":"nobody"}"""),
      expectedStatus = Status.UnprocessableEntity,
      setup = stubs => stubs.groups.find.succeedsWith(None),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.edges.registerEdge.calls.isEmpty)),
    ),
    testCase(
      description = "an edge that names no group joins the default one",
      request = json(Method.POST, URL.root / "configuration" / "edges", s"""{"id":"edge-1"}"""),
      expectedStatus = Status.Created,
      setup = stubs =>
        stubs.groups.find.succeedsWith(Some(EdgeGroupRecord(EdgeGroup.DefaultId, EdgeGroupKind.FirstParty))) *>
          stubs.edges.registerEdge.succeedsWith(testKeyPair),
      verify = (_, stubs) => ZIO.succeed(assertTrue(stubs.edges.registerEdge.calls == List((edgeId, EdgeGroup.DefaultId)))),
    ),
    testCase(
      description = "an enrollment token is created for a registered edge",
      request = Request.post(
        (URL.root / "configuration" / "edges" / "enrollment-tokens").addQueryParam("edgeId", edgeId.toString),
        Body.empty,
      ),
      expectedStatus = Status.Created,
      setup = stubs =>
        stubs.identity.issueEnrollmentToken.succeedsWith(
          EdgeIdentityService.IssuedToken("TOKEN", java.time.Instant.parse("2026-10-21T12:00:00Z")),
        ),
      verify = (response, stubs) =>
        for body <- response.body.asJson[EnrollmentTokenResponse]
        yield assertTrue(body.token == "TOKEN", stubs.identity.issueEnrollmentToken.calls == List(edgeId)),
    ),
    testCase(
      description = "a token for an edge that is not registered is 404",
      request = Request.post(
        (URL.root / "configuration" / "edges" / "enrollment-tokens").addQueryParam("edgeId", edgeId.toString),
        Body.empty,
      ),
      expectedStatus = Status.NotFound,
      setup = stubs => stubs.identity.issueEnrollmentToken.failsWith(EdgeIdentityService.Refused("edge 'edge-1' is not registered")),
    ),
  )

package versola.central

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.{EdgeId, EdgeRecord, EdgeService}
import versola.central.configuration.jwks.{JwksController, JwksService}
import versola.central.configuration.metadata.{ServerMetadataController, ServerMetadataService}
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.roles.{RoleController, RoleService}
import versola.central.users.{UserController, UserId, UserService}
import versola.util.http.Observability
import versola.util.{EnvName, JWT, RsaKeyPair}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.UUID
import versola.central.configuration.edges.EdgeGroup
import versola.central.configuration.edges.EdgeGroupKind

/** `refuseEdgeCallers` (#554), applied to a sample of the controllers it wraps in
  * `PostgresCentralApp`: the ones the issue names (`jwks/signing-keys/sync`,
  * `/users/registrations`), a sync route auth keeps reading, and an admin route. The rule is
  * the group's, not any one controller's, so the cases here are about who gets in and what the
  * routes keep answering to everyone else.
  */
object EdgeCallerRefusalSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config
  private val edgeId = EdgeId("edge-a")
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

  private val edgeKeyPair: RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(
      keyId = "edge-a-key",
      publicKey = pair.getPublic.asInstanceOf[RSAPublicKey],
      privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey],
    )

  private val edgeRecord = EdgeRecord(edgeId, edgeKeyPair.toPublicJwk, None, requireDpopNonce = true, overloadPolicy = None, accessLogPolicy = None, rateLimitPolicies = None, policyMeta = None, groupId = EdgeGroup.DefaultId, groupKind = EdgeGroupKind.FirstParty)

  private val edgeToken: Task[String] =
    JWT.serialize(
      JWT.Claims("edge", "edge", List("central"), Json.Obj()),
      1.minute,
      JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKeyPair.keyId, edgeKeyPair.privateKey),
      headers = Map("edge_id" -> edgeId.toString),
    )

  /** What someone with a rebuilt edge could mint: the edge's own key, the `edge_id` header left out so the
    * token no longer says it is an edge's. With no `edge_id` central verifies against the secret shared
    * with auth, which an edge never holds, so the signature cannot be valid. */
  private val edgeTokenWithoutEdgeId: Task[String] =
    JWT.serialize(
      JWT.Claims("edge", "edge", List("central"), Json.Obj()),
      1.minute,
      JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKeyPair.keyId, edgeKeyPair.privateKey),
    )

  private val authToken: Task[String] =
    JWT.serialize(
      JWT.Claims("auth", "auth", List("central"), Json.Obj()),
      1.minute,
      JWT.Signature.Symmetric(config.secretKey),
    )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private case class Services(
      jwks: Stub[JwksService],
      metadata: Stub[ServerMetadataService],
      users: Stub[UserService],
      roles: Stub[RoleService],
      edges: Stub[EdgeService],
  )

  private def testCase(
      description: String,
      request: Request,
      authorization: Task[Option[Header.Authorization]],
      expectedStatus: Status,
      registered: Boolean = true,
      verify: Services => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        httpClient <- ZIO.service[Client]
        services = Services(stub[JwksService], stub[ServerMetadataService], stub[UserService], stub[RoleService], stub[EdgeService])
        resourceService = stub[ResourceService]
        tracing <- tracingLayer.build
        routes = JwksController.routes ++ ServerMetadataController.routes ++ UserController.routes ++ RoleController.routes
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            (routes @@ refuseEdgeCallers).provideEnvironment(
              ZEnvironment[JwksService](services.jwks) ++
                ZEnvironment[ServerMetadataService](services.metadata) ++
                ZEnvironment[UserService](services.users) ++
                ZEnvironment[RoleService](services.roles) ++
                ZEnvironment[EdgeService](services.edges) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[CentralConfig](config) ++
                ZEnvironment[EnvName](EnvName.Test("test")) ++
                tracing,
            ),
          ),
        )
        _ <- services.edges.find.succeedsWith(Option.when(registered)(edgeRecord))
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- services.jwks.getRaw.succeedsWith(Json.Obj())
        _ <- services.jwks.getSigningKeys.succeedsWith(Map.empty)
        _ <- services.metadata.getMetadata.succeedsWith(None)
        _ <- services.users.indexRegistered.succeedsWith(userId)
        _ <- services.roles.getTenantRoles.succeedsWith(Vector.empty)
        header <- authorization
        response <- httpClient.batched(header.fold(request)(request.addHeader))
        verifyResult <- verify(services)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val asEdge = edgeToken.map(token => Some(Header.Authorization.Bearer(token)))
  private val asAuth = authToken.map(token => Some(Header.Authorization.Bearer(token)))
  private val asAdmin = ZIO.succeed(Some(TestAdminAuth.basicAuthHeader))

  private val registration =
    Request.post(URL.root / "users" / "registrations", Body.fromString("""{"email":"user@example.com"}"""))
      .addHeader(Header.ContentType(MediaType.application.json))

  private val roles = Request.get((URL.root / "configuration" / "roles").addQueryParam("tenantId", "tenant-a"))

  def spec = suite("refuseEdgeCallers")(
    suite("a registered edge is forbidden, and nothing behind the route runs")(
      testCase(
        description = "GET /configuration/jwks/sync",
        request = Request.get(URL.root / "configuration" / "jwks" / "sync"),
        authorization = asEdge,
        expectedStatus = Status.Forbidden,
        verify = services => ZIO.succeed(assertTrue(services.jwks.getRaw.calls.isEmpty)),
      ),
      testCase(
        description = "GET /configuration/jwks/signing-keys/sync",
        request = Request.get(URL.root / "configuration" / "jwks" / "signing-keys" / "sync"),
        authorization = asEdge,
        expectedStatus = Status.Forbidden,
        verify = services => ZIO.succeed(assertTrue(services.jwks.getSigningKeys.calls.isEmpty)),
      ),
      testCase(
        description = "GET /configuration/server-metadata/sync",
        request = Request.get(URL.root / "configuration" / "server-metadata" / "sync"),
        authorization = asEdge,
        expectedStatus = Status.Forbidden,
        verify = services => ZIO.succeed(assertTrue(services.metadata.getMetadata.calls.isEmpty)),
      ),
      testCase(
        description = "POST /users/registrations",
        request = registration,
        authorization = asEdge,
        expectedStatus = Status.Forbidden,
        verify = services => ZIO.succeed(assertTrue(services.users.indexRegistered.calls.isEmpty)),
      ),
      // An admin route would refuse a bearer token on its own; the group rule answers first,
      // and says why.
      testCase(
        description = "GET /configuration/roles, an admin route",
        request = roles,
        authorization = asEdge,
        expectedStatus = Status.Forbidden,
        verify = services => ZIO.succeed(assertTrue(services.roles.getTenantRoles.calls.isEmpty)),
      ),
    ),
    // Forbidden is for an edge central knows. A token that only claims to be one is not
    // authenticated at all, so it is told nothing about which edges exist.
    testCase(
      description = "a token naming an edge central has not registered is unauthenticated",
      request = Request.get(URL.root / "configuration" / "jwks" / "sync"),
      authorization = asEdge,
      registered = false,
      expectedStatus = Status.Unauthorized,
      verify = services => ZIO.succeed(assertTrue(services.jwks.getRaw.calls.isEmpty)),
    ),
    // Taking `edge_id` out of the header does not turn an edge's token into auth's: the key that decides
    // is picked by that header, and without it the token is checked against a secret the edge lacks.
    suite("an edge's token with the edge_id left out is unauthenticated, and nothing behind the route runs")(
      testCase(
        description = "GET /configuration/jwks/signing-keys/sync",
        request = Request.get(URL.root / "configuration" / "jwks" / "signing-keys" / "sync"),
        authorization = edgeTokenWithoutEdgeId.map(token => Some(Header.Authorization.Bearer(token))),
        expectedStatus = Status.Unauthorized,
        verify = services => ZIO.succeed(assertTrue(services.jwks.getSigningKeys.calls.isEmpty)),
      ),
      testCase(
        description = "POST /users/registrations",
        request = registration,
        authorization = edgeTokenWithoutEdgeId.map(token => Some(Header.Authorization.Bearer(token))),
        expectedStatus = Status.Unauthorized,
        verify = services => ZIO.succeed(assertTrue(services.users.indexRegistered.calls.isEmpty)),
      ),
    ),
    suite("everyone else is the route's own business")(
      testCase(
        description = "auth's token still reads GET /configuration/jwks/sync",
        request = Request.get(URL.root / "configuration" / "jwks" / "sync"),
        authorization = asAuth,
        expectedStatus = Status.Ok,
        verify = services => ZIO.succeed(assertTrue(services.jwks.getRaw.calls.size == 1)),
      ),
      testCase(
        description = "auth's token still reads GET /configuration/jwks/signing-keys/sync",
        request = Request.get(URL.root / "configuration" / "jwks" / "signing-keys" / "sync"),
        authorization = asAuth,
        expectedStatus = Status.Ok,
        verify = services => ZIO.succeed(assertTrue(services.jwks.getSigningKeys.calls.size == 1)),
      ),
      testCase(
        description = "auth's token still posts to /users/registrations",
        request = registration,
        authorization = asAuth,
        expectedStatus = Status.Ok,
        verify = services => ZIO.succeed(assertTrue(services.users.indexRegistered.calls.size == 1)),
      ),
      testCase(
        description = "the admin credential still lists roles",
        request = roles,
        authorization = asAdmin,
        expectedStatus = Status.Ok,
        verify = services => ZIO.succeed(assertTrue(services.roles.getTenantRoles.calls.size == 1)),
      ),
      testCase(
        description = "a bearer token that is not a JWT gets the route's own refusal",
        request = Request.get(URL.root / "configuration" / "jwks" / "sync"),
        authorization = ZIO.succeed(Some(Header.Authorization.Bearer("not-a-jwt"))),
        expectedStatus = Status.Unauthorized,
      ),
      testCase(
        description = "no credential at all gets the route's own refusal",
        request = Request.get(URL.root / "configuration" / "jwks" / "sync"),
        authorization = ZIO.none,
        expectedStatus = Status.Unauthorized,
      ),
    ),
  )

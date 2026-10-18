package versola.central.configuration.edges

import com.nimbusds.jose.jwk.RSAKey
import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.util.http.Observability
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
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeController.routes.provideEnvironment(
              ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[TenantRepository](tenantRepository) ++
                ZEnvironment(TestCentralConfig.config) ++
                tracing,
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- tenantRepository.getAll.succeedsWith(Vector.empty)
        _ <- setup(edgeService)
        response <- client.batched(request.addHeader(TestAdminAuth.basicAuthHeader))
        verifyResult <- verify(response, edgeService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  /** The edge-facing routes authenticate with `authorizeInternal`, not the Basic header every
    * other route here uses, so these cases build their own credentials. */
  private def internalTestCase(
      description: String,
      url: URL,
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
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeController.routes.provideEnvironment(
              ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[TenantRepository](tenantRepository) ++
                ZEnvironment(TestCentralConfig.config) ++
                tracing,
            ),
          ),
        )
        _ <- tenantRepository.getAll.succeedsWith(tenants)
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
          service.registerEdge.calls == List(edgeId),
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
  )

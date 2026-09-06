package versola.central.configuration.edges

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestAdminAuth
import versola.central.configuration.resources.ResourceService
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
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            EdgeController.routes.provideEnvironment(
              ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService) ++
                tracing,
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(edgeService)
        response <- client.batched(request.addHeader(TestAdminAuth.basicAuthHeader))
        verifyResult <- verify(response, edgeService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

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
            EdgeRecord(edgeId, Json.Obj(), oldPublicKey = None),
            EdgeRecord(EdgeId("edge-2"), Json.Obj(), oldPublicKey = Some(Json.Obj())),
          ),
        ),
      verify = (response, _) =>
        for body <- response.body.asJson[GetAllEdgesResponse]
        yield assertTrue(
          body.edges == List(
            EdgeResponse(edgeId, hasOldKey = false),
            EdgeResponse(EdgeId("edge-2"), hasOldKey = true),
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
  )

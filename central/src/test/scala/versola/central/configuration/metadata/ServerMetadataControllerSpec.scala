package versola.central.configuration.metadata

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.util.JWT
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object ServerMetadataControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config = TestCentralConfig.config
  private val secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES")

  private val metadata = Json.Obj("issuer" -> Json.Str("https://auth.example.com"))

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
      setup: Stub[ServerMetadataService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[ServerMetadataService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[ServerMetadataService]
        edgeService = stub[EdgeService]
        resourceService = stub[ResourceService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ServerMetadataController.routes.provideEnvironment(
              ZEnvironment[ServerMetadataService](service) ++
                ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[CentralConfig](config) ++
                tracing
            )
          )
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        requestWithAuth = request.headers.header(Header.Authorization) match
          case None => request.addHeader(TestAdminAuth.basicAuthHeader)
          case _    => request
        response <- client.batched(requestWithAuth)
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("ServerMetadataController")(
    controllerTestCase(
      description = "GET server-metadata returns the current metadata",
      request = Request.get(URL.empty / "configuration" / "server-metadata"),
      expectedStatus = Status.Ok,
      setup = service => service.getMetadata.succeedsWith(Some(metadata)),
      verify = (response, service) =>
        for body <- response.body.asString
        yield assertTrue(
          service.getMetadata.calls.length == 1,
          body.fromJson[Json] == Right(metadata),
        ),
    ),
    controllerTestCase(
      description = "GET server-metadata returns JSON null when nothing has been set",
      request = Request.get(URL.empty / "configuration" / "server-metadata"),
      expectedStatus = Status.Ok,
      setup = service => service.getMetadata.succeedsWith(None),
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.fromJson[Json] == Right(Json.Null)),
    ),
    controllerTestCase(
      description = "GET server-metadata rejects a malformed shared secret",
      request = Request.get(URL.empty / "configuration" / "server-metadata")
        .addHeader(Header.Authorization.Basic("central", "not-valid-base64!!!")),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) => ZIO.succeed(assertTrue(service.getMetadata.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "POST server-metadata upserts the metadata and returns no content",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "server-metadata",
        body = Body.fromString(metadata.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.upsertMetadata.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.upsertMetadata.calls == List(metadata))),
    ),
    controllerTestCase(
      description = "GET server-metadata/sync returns metadata for an authorized service token",
      request = Request
        .get(URL.empty / "configuration" / "server-metadata" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getMetadata.succeedsWith(Some(metadata)),
      verify = (response, service) =>
        for body <- response.body.asString
        yield assertTrue(
          service.getMetadata.calls.length == 1,
          body.fromJson[Json] == Right(metadata),
        ),
    ),
    controllerTestCase(
      description = "GET server-metadata/sync defaults to an empty object when nothing has been set",
      request = Request
        .get(URL.empty / "configuration" / "server-metadata" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getMetadata.succeedsWith(None),
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.fromJson[Json] == Right(Json.Obj())),
    ),
    controllerTestCase(
      description = "GET server-metadata/sync rejects a request without a service token",
      request = Request.get(URL.empty / "configuration" / "server-metadata" / "sync"),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) => ZIO.succeed(assertTrue(service.getMetadata.calls.isEmpty)),
    ),
  )

package versola.central.configuration.jwks

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.EdgeService
import versola.central.TestCentralConfig
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

object JwksControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val config    = TestCentralConfig.config
  private val secretKey = config.secretKey

  private val testJwks = Json.Obj(
    "keys" -> Json.Arr(
      Json.Obj("kid" -> Json.Str("test-key"), "kty" -> Json.Str("RSA"), "use" -> Json.Str("sig")),
    )
  )

  private val syncToken = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(
        JWT.serialize(
          JWT.Claims("auth", "internal-auth", List("central"), Json.Obj()),
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
      setup: Stub[JwksService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[JwksService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client      <- ZIO.service[Client]
        service     = stub[JwksService]
        edgeService = stub[EdgeService]
        tracing     <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            JwksController.routes.provideEnvironment(
              ZEnvironment[JwksService](service) ++ tracing ++ ZEnvironment(config) ++ ZEnvironment[EdgeService](edgeService)
            )
          )
        )
        _            <- setup(service)
        response     <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val testJwk = Json.Obj(
    "kid" -> Json.Str("test-key"),
    "kty" -> Json.Str("RSA"),
    "use" -> Json.Str("sig"),
  )

  def spec = suite("JwksController")(
    controllerTestCase(
      description = "GET /configuration/jwks returns stored JWKS",
      request = Request.get(URL.empty / "configuration" / "jwks"),
      expectedStatus = Status.Ok,
      setup = service => service.getRaw.succeedsWith(testJwks),
      verify = (response, service) =>
        for
          body    <- response.body.asString
          payload <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(new RuntimeException(_))
        yield assertTrue(
          service.getRaw.calls.length == 1,
          payload == testJwks,
        ),
    ),
    controllerTestCase(
      description = "POST /configuration/jwks creates a key and returns 201",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "jwks",
        body = Body.fromString(testJwk.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.createKey.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.createKey.calls == List(("test-key", testJwk)))),
    ),
    controllerTestCase(
      description = "POST /configuration/jwks returns 400 when JWK has no kid",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "jwks",
        body = Body.fromString(Json.Obj("kty" -> Json.Str("RSA")).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
    ),
    controllerTestCase(
      description = "POST /configuration/jwks returns 400 when body is not valid JSON",
      request = Request(
        method = Method.POST,
        url = URL.empty / "configuration" / "jwks",
        body = Body.fromString("not-json"),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
    ),
    controllerTestCase(
      description = "PUT /configuration/jwks updates a key and returns 204",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "jwks",
        body = Body.fromString(testJwk.toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.updateKey.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.updateKey.calls == List(("test-key", testJwk)))),
    ),
    controllerTestCase(
      description = "PUT /configuration/jwks returns 400 when JWK has no kid",
      request = Request(
        method = Method.PUT,
        url = URL.empty / "configuration" / "jwks",
        body = Body.fromString(Json.Obj("kty" -> Json.Str("RSA")).toJson),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.BadRequest,
    ),
    controllerTestCase(
      description = "DELETE /configuration/jwks removes a key and returns 204",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "configuration" / "jwks").addQueryParam("kid", "test-key"),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.deleteKey.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.deleteKey.calls == List("test-key"))),
    ),
    controllerTestCase(
      description = "GET /configuration/jwks/sync returns JWKS for authorized service token",
      request = Request
        .get(URL.empty / "configuration" / "jwks" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      setup = service => service.getRaw.succeedsWith(testJwks),
      verify = (response, service) =>
        for
          body    <- response.body.asString
          payload <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(new RuntimeException(_))
        yield assertTrue(
          service.getRaw.calls.length == 1,
          payload == testJwks,
        ),
    ),
    controllerTestCase(
      description = "GET /configuration/jwks/sync rejects request without service token",
      request = Request.get(URL.empty / "configuration" / "jwks" / "sync"),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getRaw.calls.isEmpty)),
    ),
  )

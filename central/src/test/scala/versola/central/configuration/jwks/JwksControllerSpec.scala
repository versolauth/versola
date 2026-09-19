package versola.central.configuration.jwks

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
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
      authHeader: Option[Header.Authorization] = Some(TestAdminAuth.basicAuthHeader),
      setup: Stub[JwksService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[JwksService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client             <- ZIO.service[Client]
        service            = stub[JwksService]
        edgeService        = stub[EdgeService]
        resourceService      = stub[ResourceService]
        tracing            <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            JwksController.routes.provideEnvironment(
              ZEnvironment[JwksService](service) ++ tracing ++ ZEnvironment[CentralConfig](config) ++
                ZEnvironment[EdgeService](edgeService) ++ ZEnvironment[ResourceService](resourceService)
            )
          )
        )
        _            <- resourceService.verifySecret.succeedsWith(true)
        _            <- setup(service)
        response     <- client.batched(
          authHeader.foldLeft(request.addHeader(Header.Accept(MediaType.application.json)))(_.addHeader(_))
        )
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
      description = "GET /configuration/jwks rejects request without shared secret",
      request = Request.get(URL.empty / "configuration" / "jwks"),
      expectedStatus = Status.Unauthorized,
      authHeader = None,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getRaw.calls.isEmpty)),
    ),
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
      authHeader = None,
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
      authHeader = None,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getRaw.calls.isEmpty)),
    ),
    // Retiring a key is the last step of a rotation. A key a tenant still signs with is the
    // operator having got the order wrong, so they are told which tenant to move first --
    // a 409, not the 500 an unclassified failure would give them.
    controllerTestCase(
      description = "DELETE /configuration/jwks returns 409 for a key a tenant still signs with",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "configuration" / "jwks").addQueryParam("kid", "test-key"),
      ),
      expectedStatus = Status.Conflict,
      setup = service =>
        service.deleteKey.failsWith(JwksService.Error("Key 'test-key' is the signing key of tenant-a.")),
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.contains("tenant-a")),
    ),
    controllerTestCase(
      description = "GET /configuration/jwks/keys lists each key's alg and whether it can sign",
      request = Request.get(URL.empty / "configuration" / "jwks" / "keys"),
      expectedStatus = Status.Ok,
      setup = service =>
        service.listKeys.succeedsWith(Vector(
          JwksService.KeySummary("ps-kid", Some("PS256"), Some("RSA"), None, canSign = true),
          JwksService.KeySummary("bootstrap-kid", None, Some("RSA"), None, canSign = false),
        )),
      verify = (response, _) =>
        for
          body <- response.body.asString
          payload <- ZIO.fromEither(body.fromJson[JwksController.KeysResponse]).mapError(RuntimeException(_))
        yield assertTrue(
          payload.keys.map(_.kid) == Vector("ps-kid", "bootstrap-kid"),
          payload.keys.map(_.canSign) == Vector(true, false),
          payload.keys.map(_.algorithm) == Vector(Some("PS256"), None),
        ),
    ),
    controllerTestCase(
      description = "GET /configuration/jwks/keys rejects request without shared secret",
      request = Request.get(URL.empty / "configuration" / "jwks" / "keys"),
      expectedStatus = Status.Unauthorized,
      authHeader = None,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.listKeys.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "POST /configuration/jwks/generate generates a key for the requested alg",
      request = Request(
        method = Method.POST,
        url = (URL.empty / "configuration" / "jwks" / "generate").addQueryParam("alg", "PS256"),
        body = Body.empty,
      ),
      expectedStatus = Status.Created,
      setup = service => service.generateKey.succeedsWith("generated-kid"),
      verify = (response, service) =>
        for
          body <- response.body.asString
          payload <- ZIO.fromEither(body.fromJson[JwksController.GeneratedKey]).mapError(RuntimeException(_))
        yield assertTrue(
          service.generateKey.calls == List(JWT.Algorithm.PS256),
          payload == JwksController.GeneratedKey("generated-kid", "PS256"),
        ),
    ),
    controllerTestCase(
      description = "POST /configuration/jwks/generate returns 400 for an alg this server cannot sign with",
      request = Request(
        method = Method.POST,
        url = (URL.empty / "configuration" / "jwks" / "generate").addQueryParam("alg", "RS512"),
        body = Body.empty,
      ),
      expectedStatus = Status.BadRequest,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.generateKey.calls.isEmpty)),
    ),
    // A shared secret has no public half, so there would be nothing to publish in a JWKS
    // that is not itself the signing key.
    controllerTestCase(
      description = "POST /configuration/jwks/generate returns 400 for HS256",
      request = Request(
        method = Method.POST,
        url = (URL.empty / "configuration" / "jwks" / "generate").addQueryParam("alg", "HS256"),
        body = Body.empty,
      ),
      expectedStatus = Status.BadRequest,
      setup = service => service.generateKey.failsWith(JwksService.Error("HS256 is not a JWKS signing algorithm")),
    ),
    // Only auth reads this one. Edge reads `/sync`, which publishes public halves alone:
    // what it never receives, it cannot leak.
    controllerTestCase(
      description = "GET /configuration/jwks/signing-keys/sync returns the encrypted private halves",
      request = Request
        .get(URL.empty / "configuration" / "jwks" / "signing-keys" / "sync")
        .addHeader(Header.Authorization.Bearer(syncToken)),
      expectedStatus = Status.Ok,
      authHeader = None,
      setup = service => service.getSigningKeys.succeedsWith(Map("ps-kid" -> "encrypted")),
      verify = (response, _) =>
        for
          body <- response.body.asString
          payload <- ZIO.fromEither(body.fromJson[JwksController.SigningKeysResponse]).mapError(RuntimeException(_))
        yield assertTrue(payload.privateKeys == Map("ps-kid" -> "encrypted")),
    ),
    controllerTestCase(
      description = "GET /configuration/jwks/signing-keys/sync rejects request without service token",
      request = Request.get(URL.empty / "configuration" / "jwks" / "signing-keys" / "sync"),
      expectedStatus = Status.Unauthorized,
      authHeader = None,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getSigningKeys.calls.isEmpty)),
    ),
    // The admin JWKS view must not become a second channel for key material.
    controllerTestCase(
      description = "GET /configuration/jwks/signing-keys/sync rejects the admin basic credentials",
      request = Request.get(URL.empty / "configuration" / "jwks" / "signing-keys" / "sync"),
      expectedStatus = Status.Unauthorized,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.getSigningKeys.calls.isEmpty)),
    ),
  )

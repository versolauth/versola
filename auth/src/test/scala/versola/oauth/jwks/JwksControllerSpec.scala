package versola.oauth.jwks

import versola.auth.TestEnvConfig
import versola.util.http.NoopTracing
import versola.util.UnitSpecBase
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.*
import zio.test.*

object JwksControllerSpec extends UnitSpecBase:

  def jwksTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        config = TestEnvConfig.coreConfig
        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          JwksController.routes
            .provideEnvironment(ZEnvironment(config) ++ tracing)
        )

        response <- client.batched(request)
        verifyResult <- verify(response)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("JwksController")(
    suite("GET /.well-known/jwks.json")(
      jwksTestCase(
        description = "successfully return JWKS with public keys",
        request = Request.get(
          url = URL.empty / ".well-known" / "jwks.json"
        ),
        expectedStatus = Status.Ok,
        verify = response =>
          for
            contentType <- ZIO.fromOption(response.header(Header.ContentType))
              .orElseFail(new RuntimeException("Missing Content-Type header"))
            cacheControl <- ZIO.fromOption(response.header(Header.CacheControl))
              .orElseFail(new RuntimeException("Missing Cache-Control header"))
            body <- response.body.asString
            jwks <- ZIO.fromEither(body.fromJson[Json]).mapError(new RuntimeException(_))
          yield assertTrue(
            contentType.mediaType == MediaType.application.json,
            cacheControl.renderedValue.contains("public"),
            cacheControl.renderedValue.contains("max-age=86400"),
            jwks.isInstanceOf[Json.Obj],
          ),
      ),

      jwksTestCase(
        description = "return JWKS with exact expected structure",
        request = Request.get(
          url = URL.empty / ".well-known" / "jwks.json"
        ),
        expectedStatus = Status.Ok,
        verify = response =>
          for
            body <- response.body.asString
            actualJson <- ZIO.fromEither(body.fromJson[Json]).mapError(new RuntimeException(_))

            // Get the expected JWKS from TestEnvConfig
            expectedJson = TestEnvConfig.jwksJson
          yield assertTrue(
            actualJson == expectedJson,
          ),
      ),

      jwksTestCase(
        description = "cache headers should allow 24 hour caching",
        request = Request.get(
          url = URL.empty / ".well-known" / "jwks.json"
        ),
        expectedStatus = Status.Ok,
        verify = response =>
          for
            cacheControl <- ZIO.fromOption(response.header(Header.CacheControl))
              .orElseFail(new RuntimeException("Missing Cache-Control header"))
          yield assertTrue(
            cacheControl.renderedValue.contains("public"),
            cacheControl.renderedValue.contains("max-age=86400"),
          ),
      ),
    ),
  )


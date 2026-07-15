package versola.oauth.introspect

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, ClientIdWithSecret}
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.util.{Base64, Secret, UnitSpecBase}
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.test.*
import zio.test.TestAspect

object IntrospectionControllerSpec extends UnitSpecBase:

  private val clientId     = ClientId("test-client")
  private val clientSecret = Secret(Array.fill(32)(4.toByte))

  def authHeader(id: ClientId, secret: Secret): Header.Authorization =
    Header.Authorization.Basic(id, Base64.urlEncode(secret))

  def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stub[IntrospectionService] => UIO[Unit] = _ => ZIO.unit,
      verify: Response => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client              <- ZIO.service[Client]
        introspectionService = stub[IntrospectionService]
        jwksService          = TestEnvConfig.jwksService
        config               = TestEnvConfig.coreConfig
        tracing             <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            IntrospectionController.routes
              .provideEnvironment(
                ZEnvironment(introspectionService) ++
                  ZEnvironment(jwksService) ++
                  ZEnvironment(config) ++
                  tracing
              )
          )
        )
        _ <- setup(introspectionService)

        response     <- client.batched(request)
        verifyResult <- verify(response)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("IntrospectionController")(
    suite("POST /introspect")(
      controllerTestCase(
        description = "returns 401 when Basic auth is missing",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> "some-token")),
        ),
        expectedStatus = Status.Unauthorized,
      ),
      controllerTestCase(
        description = "returns 200 with inactive when IntrospectionService fails with Unauthenticated",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> Base64.urlEncode(Array.fill(32)(1.toByte)))),
        ).addHeader(authHeader(clientId, clientSecret)),
        expectedStatus = Status.Unauthorized,
        setup = svc =>
          svc.introspectRefreshToken.failsWith(IntrospectionError.Unauthenticated),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("\"error\":\"invalid_client\"")),
      ),
      controllerTestCase(
        description = "returns 200 with active when IntrospectionService succeeds",
        request = Request.post(
          url = URL.root / "introspect",
          body = Body.fromURLEncodedForm(Form.fromStrings("token" -> Base64.urlEncode(Array.fill(32)(2.toByte)))),
        ).addHeader(authHeader(clientId, clientSecret)),
        expectedStatus = Status.Ok,
        setup = svc =>
          svc.introspectRefreshToken.succeedsWith(IntrospectionResponse.Inactive.copy(active = true)),
        verify = response =>
          for body <- response.body.asString
          yield assertTrue(body.contains("\"active\":true")),
      ),
    ),
  )

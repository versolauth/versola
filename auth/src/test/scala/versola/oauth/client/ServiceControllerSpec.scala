package versola.oauth.client

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.auth.TestEnvConfig
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.EnvName
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.json.ast.Json as JsonAst
import zio.test.*

import java.util.UUID

object ServiceControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

  private val internalAuthHeader: Task[Header] =
    versola.util.JWT.serialize(
      versola.util.JWT.Claims("auth", "auth", List("central"), JsonAst.Obj()),
      1.minute,
      versola.util.JWT.Signature.Symmetric(TestEnvConfig.coreConfig.central.secretKey),
    ).map(token => Header.Authorization.Bearer(token))

  private type Stubs = (Stub[OAuthConfigurationService], Stub[UserRepository])

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      env: EnvName = EnvName.Test("test"),
      authenticate: Boolean = true,
      setup: Stubs => UIO[Unit] = _ => ZIO.unit,
      verify: Stubs => Task[TestResult] = _ => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        configuration = stub[OAuthConfigurationService]
        userRepository = stub[UserRepository]
        stubs = (configuration, userRepository)
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ServiceController.routes.provideEnvironment(
              ZEnvironment[OAuthConfigurationService](configuration) ++
                ZEnvironment[UserRepository](userRepository) ++
                ZEnvironment[EnvName](env) ++
                ZEnvironment(TestEnvConfig.coreConfig) ++
                tracing
            )
          )
        )
        _ <- setup(stubs)
        authHeader <- if authenticate then internalAuthHeader.map(Some(_)) else ZIO.succeed(None)
        requestWithAuth = authHeader.fold(request)(request.addHeader(_))
        response <- client.batched(requestWithAuth)
        verifyResult <- verify(stubs)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("ServiceController")(
    suite("POST /service/configuration/sync")(
      controllerTestCase(
        description = "syncs configuration and returns 200 OK",
        request = Request(method = Method.POST, url = URL.empty / "service" / "configuration" / "sync"),
        expectedStatus = Status.Ok,
        setup = (configuration, _) => configuration.syncConfiguration.succeedsWith(()),
        verify = (configuration, _) => ZIO.succeed(assertTrue(configuration.syncConfiguration.calls.length == 1)),
      ),
      controllerTestCase(
        description = "rejects a request without a valid internal auth token",
        request = Request(method = Method.POST, url = URL.empty / "service" / "configuration" / "sync"),
        expectedStatus = Status.Unauthorized,
        authenticate = false,
        verify = (configuration, _) => ZIO.succeed(assertTrue(configuration.syncConfiguration.calls.isEmpty)),
      ),
      controllerTestCase(
        description = "returns 404 Not Found in prod, without even checking auth",
        request = Request(method = Method.POST, url = URL.empty / "service" / "configuration" / "sync"),
        expectedStatus = Status.NotFound,
        env = EnvName.Prod,
        authenticate = false,
        verify = (configuration, _) => ZIO.succeed(assertTrue(configuration.syncConfiguration.calls.isEmpty)),
      ),
    ),
    suite("DELETE /service/users")(
      controllerTestCase(
        description = "deletes the user and returns 204 No Content",
        request = Request(method = Method.DELETE, url = (URL.empty / "service" / "users").addQueryParam("id", userId.toString)),
        expectedStatus = Status.NoContent,
        setup = (_, userRepository) => userRepository.delete.succeedsWith(()),
        verify = (_, userRepository) => ZIO.succeed(assertTrue(userRepository.delete.calls == List(userId))),
      ),
      controllerTestCase(
        description = "returns 404 Not Found in prod",
        request = Request(method = Method.DELETE, url = (URL.empty / "service" / "users").addQueryParam("id", userId.toString)),
        expectedStatus = Status.NotFound,
        env = EnvName.Prod,
        authenticate = false,
        verify = (_, userRepository) => ZIO.succeed(assertTrue(userRepository.delete.calls.isEmpty)),
      ),
    ),
  )

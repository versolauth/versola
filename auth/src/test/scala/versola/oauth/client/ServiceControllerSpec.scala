package versola.oauth.client

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.auth.TestEnvConfig
import versola.oauth.jwks.JwksService
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

  private type Stubs = (Stub[OAuthConfigurationService], Stub[UserRepository], Stub[JwksService])

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
        jwksService = stub[JwksService]
        stubs = (configuration, userRepository, jwksService)
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ServiceController.routes.provideEnvironment(
              ZEnvironment[OAuthConfigurationService](configuration) ++
                ZEnvironment[UserRepository](userRepository) ++
                ZEnvironment[JwksService](jwksService) ++
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
        setup = (configuration, _, jwksService) =>
          configuration.syncConfiguration.succeedsWith(()) *> jwksService.refresh.succeedsWith(()),
        // The JWKS is reloaded by the same call: a tenant's selected key is only signable
        // here once it has been synced, so a sync that skipped it would leave a freshly
        // generated key unusable until the next scheduled refresh.
        verify = (configuration, _, jwksService) => ZIO.succeed(assertTrue(
          configuration.syncConfiguration.calls.length == 1,
          jwksService.refresh.calls.length == 1,
        )),
      ),
      controllerTestCase(
        description = "rejects a request without a valid internal auth token",
        request = Request(method = Method.POST, url = URL.empty / "service" / "configuration" / "sync"),
        expectedStatus = Status.Unauthorized,
        authenticate = false,
        verify = (configuration, _, jwksService) => ZIO.succeed(assertTrue(
          configuration.syncConfiguration.calls.isEmpty,
          jwksService.refresh.calls.isEmpty,
        )),
      ),
      controllerTestCase(
        description = "returns 404 Not Found in prod, without even checking auth",
        request = Request(method = Method.POST, url = URL.empty / "service" / "configuration" / "sync"),
        expectedStatus = Status.NotFound,
        env = EnvName.Prod,
        authenticate = false,
        verify = (configuration, _, jwksService) => ZIO.succeed(assertTrue(
          configuration.syncConfiguration.calls.isEmpty,
          jwksService.refresh.calls.isEmpty,
        )),
      ),
    ),
    suite("DELETE /service/users")(
      controllerTestCase(
        description = "deletes the user and returns 204 No Content",
        request = Request(method = Method.DELETE, url = (URL.empty / "service" / "users").addQueryParam("id", userId.toString)),
        expectedStatus = Status.NoContent,
        setup = (_, userRepository, _) => userRepository.delete.succeedsWith(()),
        verify = (_, userRepository, _) => ZIO.succeed(assertTrue(userRepository.delete.calls == List(userId))),
      ),
      controllerTestCase(
        description = "returns 404 Not Found in prod",
        request = Request(method = Method.DELETE, url = (URL.empty / "service" / "users").addQueryParam("id", userId.toString)),
        expectedStatus = Status.NotFound,
        env = EnvName.Prod,
        authenticate = false,
        verify = (_, userRepository, _) => ZIO.succeed(assertTrue(userRepository.delete.calls.isEmpty)),
      ),
    ),
  )

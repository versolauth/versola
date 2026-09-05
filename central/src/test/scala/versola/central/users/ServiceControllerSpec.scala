package versola.central.users

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.TestAdminAuth
import versola.central.configuration.resources.ResourceService
import versola.util.EnvName
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

object ServiceControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private type Stubs = (Stub[UserOutboxProcessor], Stub[AuthClient], Stub[UserService], Stub[ResourceService])

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
        outboxProcessor = stub[UserOutboxProcessor]
        authClient = stub[AuthClient]
        userService = stub[UserService]
        resourceService = stub[ResourceService]
        stubs = (outboxProcessor, authClient, userService, resourceService)
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            ServiceController.routes.provideEnvironment(
              ZEnvironment[UserOutboxProcessor](outboxProcessor) ++
                ZEnvironment[AuthClient](authClient) ++
                ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[EnvName](env) ++
                ZEnvironment[UserService](userService) ++
                tracing
            )
          )
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(stubs)
        requestWithAuth = if authenticate then request.addHeader(TestAdminAuth.basicAuthHeader) else request
        response <- client.batched(requestWithAuth)
        verifyResult <- verify(stubs)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("ServiceController")(
    suite("POST /service/users/outbox/flush")(
      controllerTestCase(
        description = "flushes the outbox and returns 200 OK",
        request = Request(method = Method.POST, url = URL.empty / "service" / "users" / "outbox" / "flush"),
        expectedStatus = Status.Ok,
        setup = (outboxProcessor, _, _, _) => outboxProcessor.flush().succeedsWith(()),
        verify = (outboxProcessor, _, _, _) => ZIO.succeed(assertTrue(outboxProcessor.flush().calls.length == 1)),
      ),
      controllerTestCase(
        description = "rejects a request without central's shared secret",
        request = Request(method = Method.POST, url = URL.empty / "service" / "users" / "outbox" / "flush"),
        expectedStatus = Status.Unauthorized,
        authenticate = false,
        verify = (outboxProcessor, _, _, _) => ZIO.succeed(assertTrue(outboxProcessor.flush().calls.isEmpty)),
      ),
      controllerTestCase(
        description = "returns 404 Not Found in prod, without even checking auth",
        request = Request(method = Method.POST, url = URL.empty / "service" / "users" / "outbox" / "flush"),
        expectedStatus = Status.NotFound,
        env = EnvName.Prod,
        authenticate = false,
        verify = (outboxProcessor, _, _, _) => ZIO.succeed(assertTrue(outboxProcessor.flush().calls.isEmpty)),
      ),
    ),
    suite("POST /service/configuration/sync")(
      controllerTestCase(
        description = "syncs configuration and returns 200 OK",
        request = Request(method = Method.POST, url = URL.empty / "service" / "configuration" / "sync"),
        expectedStatus = Status.Ok,
        setup = (_, authClient, _, _) => authClient.syncConfiguration().succeedsWith(()),
        verify = (_, authClient, _, _) => ZIO.succeed(assertTrue(authClient.syncConfiguration().calls.length == 1)),
      ),
      controllerTestCase(
        description = "returns 404 Not Found in prod",
        request = Request(method = Method.POST, url = URL.empty / "service" / "configuration" / "sync"),
        expectedStatus = Status.NotFound,
        env = EnvName.Prod,
        authenticate = false,
        verify = (_, authClient, _, _) => ZIO.succeed(assertTrue(authClient.syncConfiguration().calls.isEmpty)),
      ),
    ),
    suite("DELETE /service/users")(
      controllerTestCase(
        description = "deletes the user and returns 204 No Content",
        request = Request(method = Method.DELETE, url = (URL.empty / "service" / "users").addQueryParam("id", userId.toString)),
        expectedStatus = Status.NoContent,
        setup = (_, _, userService, _) => userService.delete.succeedsWith(()),
        verify = (_, _, userService, _) => ZIO.succeed(assertTrue(userService.delete.calls == List(userId))),
      ),
      controllerTestCase(
        description = "returns 404 Not Found in prod",
        request = Request(method = Method.DELETE, url = (URL.empty / "service" / "users").addQueryParam("id", userId.toString)),
        expectedStatus = Status.NotFound,
        env = EnvName.Prod,
        authenticate = false,
        verify = (_, _, userService, _) => ZIO.succeed(assertTrue(userService.delete.calls.isEmpty)),
      ),
    ),
  )

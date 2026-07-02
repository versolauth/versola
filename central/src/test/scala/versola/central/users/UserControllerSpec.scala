package versola.central.users

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.central.configuration.clients.OAuthClientService
import versola.util.Email
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

object UserControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val email  = Email("user@example.com")

  private val passkeyInfo = PasskeyInfo(
    id = "aQ",
    name = Some("Phone"),
    deviceType = "MultiDevice",
    transports = List("Internal"),
    backedUp = true,
    backupEligible = true,
    lastUsedAt = None,
    createdAt = "2024-01-01T00:00:00Z",
  )

  private val createRequest = CreateUserRequest(
    email = Some(email),
    phone = None,
    login = None,
  )

  private val createRequestBody =
    """{"email":"user@example.com"}"""

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
      setup: Stub[UserService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[UserService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client      <- ZIO.service[Client]
        service     =  stub[UserService]
        oauthClientService = stub[OAuthClientService]
        tracing     <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes.provideEnvironment(
              ZEnvironment[UserService](service) ++
                ZEnvironment(TestCentralConfig.config) ++
                tracing ++ ZEnvironment[OAuthClientService](oauthClientService)
            )
          )
        )
        _ <- oauthClientService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        response <- client.batched(
          request
            .addHeader(Header.Accept(MediaType.application.json))
            .addHeader(TestAdminAuth.basicAuthHeader)
        )
        verifyResult <- verify(response, service)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("UserController")(
    controllerTestCase(
      description = "create user returns generated id",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users",
        body = Body.fromString(createRequestBody),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Created,
      setup = service => service.create.succeedsWith(userId),
      verify = (response, service) =>
        for body <- response.body.asJson[CreateUserResponse]
        yield assertTrue(
          service.create.calls == List(createRequest),
          body == CreateUserResponse(userId),
        ),
    ),
    controllerTestCase(
      description = "create user returns 409 Conflict when service signals UserConflict",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users",
        body = Body.fromString(createRequestBody),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Conflict,
      setup = service => service.create.failsWith(UserConflict),
      verify = (response, _) =>
        for body <- response.body.asString
        yield assertTrue(body.isEmpty),
    ),
    controllerTestCase(
      description = "patch claims returns 202 Accepted",
      request = Request(
        method = Method.PATCH,
        url = URL.empty / "users" / "claims",
        body = Body.fromString(s"""{"id":"$userId","claims":{"test":true}}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Accepted,
      setup = service => service.patchClaims.succeedsWith(()),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(service.patchClaims.calls.nonEmpty)),
    ),
    controllerTestCase(
      description = "patch roles returns 202 Accepted",
      request = Request(
        method = Method.PATCH,
        url = URL.empty / "users" / "roles",
        body = Body.fromString(s"""{"userId":"$userId","tenantId":"t1","add":["r1"],"remove":[]}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Accepted,
      setup = service => service.updateRoles.succeedsWith(()),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(service.updateRoles.calls.nonEmpty)),
    ),
    controllerTestCase(
      description = "list passkeys returns the user's passkeys",
      request = Request(
        method = Method.GET,
        url = (URL.empty / "users" / "passkeys").addQueryParam("id", userId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.listPasskeys.succeedsWith(List(passkeyInfo)),
      verify = (response, service) =>
        for body <- response.body.asJson[ListPasskeysResponse]
        yield assertTrue(
          service.listPasskeys.calls == List(userId),
          body == ListPasskeysResponse(List(passkeyInfo)),
        ),
    ),
    controllerTestCase(
      description = "rename passkey returns 202 Accepted",
      request = Request(
        method = Method.PATCH,
        url = URL.empty / "users" / "passkeys",
        body = Body.fromString(s"""{"userId":"$userId","credentialId":"cred-1","name":"New Name"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Accepted,
      setup = service => service.renamePasskey.succeedsWith(()),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(
          service.renamePasskey.calls == List(RenamePasskeyRequest(userId, "cred-1", Some("New Name"))),
        )),
    ),
    controllerTestCase(
      description = "delete passkey returns 202 Accepted",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "users" / "passkeys")
          .addQueryParam("id", userId.toString)
          .addQueryParam("credentialId", "cred-1"),
      ),
      expectedStatus = Status.Accepted,
      setup = service => service.deletePasskey.succeedsWith(()),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(
          service.deletePasskey.calls == List((userId, "cred-1")),
        )),
    ),
  )

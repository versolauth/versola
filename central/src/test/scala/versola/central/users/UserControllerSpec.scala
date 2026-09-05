package versola.central.users

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.http.Observability
import versola.util.{Email, EnvName, JWT, Phone}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

object UserControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val email = Email("user@example.com")

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

  private val registeredRequest = RegisteredUserRequest(
    email = Some(email),
    phone = None,
    login = None,
  )

  private val registeredRequestBody = s"""{"email":"$email"}"""

  private val internalAuthorization: Task[Header] =
    JWT.serialize(
      JWT.Claims("auth", "auth", List("central"), Json.Obj()),
      1.minute,
      JWT.Signature.Symmetric(TestCentralConfig.config.secretKey),
    ).map(token => Header.Authorization.Bearer(token))

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
      env: EnvName = EnvName.Test("test"),
      setup: Stub[UserService] => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stub[UserService]) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
      authorization: Task[Header] = ZIO.succeed(TestAdminAuth.basicAuthHeader),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service = stub[UserService]
        resourceService = stub[ResourceService]
        edgeService = stub[EdgeService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes.provideEnvironment(
              ZEnvironment[UserService](service) ++
                ZEnvironment[CentralConfig](TestCentralConfig.config) ++
                tracing ++ ZEnvironment[ResourceService](resourceService) ++
                ZEnvironment[EdgeService](edgeService) ++
                ZEnvironment[EnvName](env),
            ),
          ),
        )
        _ <- resourceService.verifySecret.succeedsWith(true)
        _ <- setup(service)
        authorizationHeader <- authorization
        response <- client.batched(
          request
            .addHeader(Header.Accept(MediaType.application.json))
            .addHeader(authorizationHeader),
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
      description = "registered user returns 200 OK with the claimed userId",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "registrations",
        body = Body.fromString(registeredRequestBody),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Ok,
      setup = service => service.indexRegistered.succeedsWith(userId),
      authorization = internalAuthorization,
      verify = (response, service) =>
        for body <- response.body.asJson[RegisteredUserResponse]
        yield assertTrue(
          service.indexRegistered.calls == List(registeredRequest),
          body == RegisteredUserResponse(userId),
        ),
    ),
    controllerTestCase(
      description = "registered user maps UserIndexConflict to 409 Conflict",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "registrations",
        body = Body.fromString(registeredRequestBody),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Conflict,
      setup = service => service.indexRegistered.failsWith(UserIndexConflict),
      authorization = internalAuthorization,
    ),
    controllerTestCase(
      description = "registered user forwards the login field when present",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "registrations",
        body = Body.fromString(s"""{"email":"$email","login":"jdoe"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Ok,
      setup = service => service.indexRegistered.succeedsWith(userId),
      authorization = internalAuthorization,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.indexRegistered.calls == List(registeredRequest.copy(login = Some(Login("jdoe")))),
        )),
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
    controllerTestCase(
      description = "find users by id returns search result",
      request = Request(
        method = Method.GET,
        url = (URL.empty / "users").addQueryParam("id", userId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.findById.succeedsWith(Some(UserSearchRecord(userId, Some(email), None, None, Json.Obj()))),
      verify = (response, _) =>
        for body <- response.body.asJson[UserSearchResponse]
        yield assertTrue(body.users.head.id == userId),
    ),
    controllerTestCase(
      description = "find users by email returns search result",
      request = Request(
        method = Method.GET,
        url = (URL.empty / "users").addQueryParam("email", email.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.findByEmail.succeedsWith(Some(UserSearchRecord(userId, Some(email), None, None, Json.Obj()))),
      verify = (response, service) =>
        for body <- response.body.asJson[UserSearchResponse]
        yield assertTrue(service.findByEmail.calls == List(email), body.users.head.id == userId),
    ),
    controllerTestCase(
      description = "find users by phone returns search result",
      request = Request(
        method = Method.GET,
        url = URL.decode("/users?phone=%2B16502530000").toOption.get,
      ),
      expectedStatus = Status.Ok,
      setup = service => service.findByPhone.succeedsWith(Some(UserSearchRecord(userId, None, Some(Phone("+16502530000")), None, Json.Obj()))),
      verify = (response, service) =>
        for body <- response.body.asJson[UserSearchResponse]
        yield assertTrue(service.findByPhone.calls == List(Phone("+16502530000")), body.users.head.id == userId),
    ),
    controllerTestCase(
      description = "find users by login returns search result",
      request = Request(
        method = Method.GET,
        url = (URL.empty / "users").addQueryParam("login", "jdoe"),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.findByLogin.succeedsWith(Some(UserSearchRecord(userId, None, None, Some(Login("jdoe")), Json.Obj()))),
      verify = (response, service) =>
        for body <- response.body.asJson[UserSearchResponse]
        yield assertTrue(service.findByLogin.calls == List(Login("jdoe")), body.users.head.id == userId),
    ),
    controllerTestCase(
      description = "find users with no query parameter returns 400 Bad Request",
      request = Request(method = Method.GET, url = URL.empty / "users"),
      expectedStatus = Status.BadRequest,
    ),
    controllerTestCase(
      description = "get user roles returns the user's roles",
      request = Request(
        method = Method.GET,
        url = (URL.empty / "users" / "roles")
          .addQueryParam("id", userId.toString)
          .addQueryParam("tenantId", "default"),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getRoles.succeedsWith(List(RoleId("admin"))),
      verify = (response, service) =>
        for body <- response.body.asJson[UserRolesResponse]
        yield assertTrue(
          service.getRoles.calls == List((userId, TenantId("default"))),
          body == UserRolesResponse(List(RoleId("admin"))),
        ),
    ),
    controllerTestCase(
      description = "get user sessions returns the user's sessions",
      request = Request(
        method = Method.GET,
        url = (URL.empty / "users" / "sessions").addQueryParam("id", userId.toString),
      ),
      expectedStatus = Status.Ok,
      setup = service => service.getSessions.succeedsWith(Nil),
      verify = (response, service) =>
        ZIO.succeed(assertTrue(service.getSessions.calls == List(userId))),
    ),
    controllerTestCase(
      description = "invalidate session returns 204 No Content",
      request = Request(
        method = Method.DELETE,
        url = (URL.empty / "users" / "sessions").addQueryParam("userId", userId.toString),
      ),
      expectedStatus = Status.NoContent,
      setup = service => service.invalidateSession.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.invalidateSession.calls == List(userId))),
    ),
    controllerTestCase(
      description = "patch user returns 202 Accepted",
      request = Request(
        method = Method.PATCH,
        url = URL.empty / "users",
        body = Body.fromString(s"""{"id":"$userId","email":"new@example.com"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Accepted,
      setup = service => service.patch.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.patch.calls.nonEmpty)),
    ),
    controllerTestCase(
      description = "reset user limits returns 202 Accepted",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "limits" / "reset",
        body = Body.fromString(s"""{"userId":"$userId","tenantId":"default"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Accepted,
      setup = service => service.resetLimits.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(
          service.resetLimits.calls == List(ResetUserLimitsRequest(userId, TenantId("default"), None, None)),
        )),
    ),
    controllerTestCase(
      description = "create user surfaces an unexpected failure as 500 Internal Server Error",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users",
        body = Body.fromString(createRequestBody),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.InternalServerError,
      setup = service => service.create.failsWith(RuntimeException("boom")),
    ),
    controllerTestCase(
      description = "registered user surfaces an unexpected failure as 500 Internal Server Error",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "registrations",
        body = Body.fromString(registeredRequestBody),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.InternalServerError,
      setup = service => service.indexRegistered.failsWith(RuntimeException("boom")),
      authorization = internalAuthorization,
    ),
    controllerTestCase(
      description = "reset password returns 204 No Content",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "password" / "reset",
        body = Body.fromString(s"""{"userId":"$userId","expiresInSeconds":43200,"channel":"email"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.resetPassword.succeedsWith(None),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.resetPassword.calls.nonEmpty)),
    ),
    controllerTestCase(
      description = "reset password with the show channel returns the plaintext in non-prod",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "password" / "reset",
        body = Body.fromString(s"""{"userId":"$userId","expiresInSeconds":43200,"channel":"show"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.Ok,
      setup = service => service.resetPassword.succeedsWith(Some("Temp1234!")),
      verify = (response, service) =>
        for body <- response.body.asJson[ResetPasswordResponse]
        yield assertTrue(body.password == "Temp1234!", service.resetPassword.calls.nonEmpty),
    ),
    controllerTestCase(
      description = "reset password with the show channel returns 404 Not Found in prod",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "password" / "reset",
        body = Body.fromString(s"""{"userId":"$userId","expiresInSeconds":43200,"channel":"show"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NotFound,
      env = EnvName.Prod,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.resetPassword.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "set password returns 204 No Content in non-prod",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "password" / "set",
        body = Body.fromString(s"""{"userId":"$userId","password":"Secret123!"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NoContent,
      setup = service => service.setPassword.succeedsWith(()),
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.setPassword.calls == List((userId, "Secret123!")))),
    ),
    controllerTestCase(
      description = "set password returns 404 Not Found in prod",
      request = Request(
        method = Method.POST,
        url = URL.empty / "users" / "password" / "set",
        body = Body.fromString(s"""{"userId":"$userId","password":"Secret123!"}"""),
      ).addHeader(Header.ContentType(MediaType.application.json)),
      expectedStatus = Status.NotFound,
      env = EnvName.Prod,
      verify = (_, service) =>
        ZIO.succeed(assertTrue(service.setPassword.calls.isEmpty)),
    ),
  )

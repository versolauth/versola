package versola.central.users

import io.opentelemetry.api
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.util.Email
import versola.util.http.Observability
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
  private val email  = Email("user@example.com")

  private val createRequest = CreateUserRequest(
    email = Some(email),
    phone = None,
    login = None,
    claims = Json.Obj(
      "email_verified"        -> Json.Bool(true),
      "phone_number_verified" -> Json.Bool(false),
      "given_name"            -> Json.Str("Alice"),
    ),
  )

  private val createRequestBody =
    """{"email":"user@example.com","claims":{"email_verified":true,"phone_number_verified":false,"given_name":"Alice"}}"""

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
        client <- ZIO.service[Client]
        service = stub[UserService]
        tracing <- tracingLayer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            UserController.routes.provideEnvironment(ZEnvironment[UserService](service) ++ tracing)
          )
        )
        _ <- setup(service)
        response <- client.batched(request.addHeader(Header.Accept(MediaType.application.json)))
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
  )

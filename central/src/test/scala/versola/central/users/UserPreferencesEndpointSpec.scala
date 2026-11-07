package versola.central.users

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.audit.AuditHook
import versola.central.audit.AuditReportService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, TestAdminAuth, TestCentralConfig}
import versola.util.EnvName
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.util.UUID

/** `GET` and `PATCH /users/preferences`: the calling administrator's own `zoneinfo`. The user is the `actorId` edge injected, and there is
  * nothing in the request that names another. */
object UserPreferencesEndpointSpec extends ZIOSpecDefault, ZIOStubs:
  private val admin = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
  private val other = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val path = URL.empty / "users" / "preferences"

  /** The request as edge forwards it: the token's `sub` as `actorId`. */
  private def asAdmin(url: URL = path, id: UserId = admin) = url.addQueryParam(AuditHook.ActorIdParam, id.toString)

  private def run(request: Request, service: UserService, authorized: Boolean = true) =
    for
      client <- ZIO.service[Client]
      resources = stub[ResourceService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          UserController.routes.provideEnvironment(
            ZEnvironment[UserService](service) ++ ZEnvironment[ResourceService](resources) ++ ZEnvironment[EdgeService](stub[EdgeService]) ++
              ZEnvironment[CentralConfig](TestCentralConfig.config) ++ ZEnvironment[EnvName](EnvName.Test("test")) ++
              ZEnvironment[AuditReportService](stub[AuditReportService]) ++ tracing,
          ),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private def patch(url: URL, body: String) =
    Request(method = Method.PATCH, url = url, body = Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))

  def spec = suite("UserController: the administrator's own preferences")(
    suite("GET /users/preferences")(
      test("answers the zoneinfo of the user the token names") {
        val service = stub[UserService]
        for
          _ <- service.getZoneinfo.succeedsWith(Some("Asia/Almaty"))
          response <- run(Request.get(asAdmin()), service)
          body <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body == """{"zoneinfo":"Asia/Almaty"}""", service.getZoneinfo.calls == List(admin))
      },
      test("answers null when nothing usable is stored") {
        val service = stub[UserService]
        for
          _ <- service.getZoneinfo.succeedsWith(None)
          response <- run(Request.get(asAdmin()), service)
          body <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body == """{"zoneinfo":null}""")
      },
      test("is refused without the central credential, and reads nothing") {
        val service = stub[UserService]
        for response <- run(Request.get(asAdmin()), service, authorized = false)
        yield assertTrue(response.status == Status.Unauthorized, service.getZoneinfo.calls.isEmpty)
      },
      test("a call with no actor, an actor that is no user, or a client's own token has no administrator behind it and reads nothing") {
        val service = stub[UserService]
        val client = path.addQueryParam(AuditHook.ActorIdParam, "orders-client").addQueryParam(AuditHook.ActorClientParam, "orders-client")
        val asClient = path.addQueryParam(AuditHook.ActorIdParam, admin.toString).addQueryParam(AuditHook.ActorClientParam, admin.toString)
        for
          none <- run(Request.get(path), service)
          notAUser <- run(Request.get(client), service)
          credentials <- run(Request.get(asClient), service)
        yield assertTrue(
          List(none, notAUser, credentials).forall(_.status == Status.Unauthorized),
          service.getZoneinfo.calls.isEmpty,
        )
      },
      test("a user named by `id` or `userId` is not the subject: only the actor is read") {
        val service = stub[UserService]
        for
          _ <- service.getZoneinfo.succeedsWith(None)
          _ <- run(Request.get(asAdmin().addQueryParam("id", other.toString).addQueryParam("userId", other.toString)), service)
        yield assertTrue(service.getZoneinfo.calls == List(admin))
      },
    ),
    suite("PATCH /users/preferences")(
      test("stores a name of the time zone database for the user the token names") {
        val service = stub[UserService]
        for
          _ <- service.setZoneinfo.succeedsWith(())
          response <- run(patch(asAdmin(), """{"zoneinfo":"Asia/Almaty"}"""), service)
        yield assertTrue(response.status == Status.NoContent, service.setZoneinfo.calls == List((admin, Some("Asia/Almaty"))))
      },
      test("null clears it") {
        val service = stub[UserService]
        for
          _ <- service.setZoneinfo.succeedsWith(())
          response <- run(patch(asAdmin(), """{"zoneinfo":null}"""), service)
        yield assertTrue(response.status == Status.NoContent, service.setZoneinfo.calls == List((admin, None)))
      },
      test("a body without zoneinfo changes nothing") {
        val service = stub[UserService]
        for response <- run(patch(asAdmin(), "{}"), service)
        yield assertTrue(response.status == Status.NoContent, service.setZoneinfo.calls.isEmpty)
      },
      test("what is not a name of the database is a bad request with the error code, and stores nothing") {
        val service = stub[UserService]
        val bad = List("+05:00", "UTC+5", "Mars/Olympus", "asia/almaty", "", " Asia/Almaty", "Asia/Almaty\n", "A" * 65)
        for
          responses <- ZIO.foreach(bad)(name => run(patch(asAdmin(), s"""{"zoneinfo":${zio.json.ast.Json.Str(name)}}"""), service))
          number <- run(patch(asAdmin(), """{"zoneinfo":5}"""), service)
        yield assertTrue(
          responses.forall(_.status == Status.BadRequest),
          number.status == Status.BadRequest,
          service.setZoneinfo.calls.isEmpty,
        )
      },
      test("is refused without the central credential, and stores nothing") {
        val service = stub[UserService]
        for response <- run(patch(asAdmin(), """{"zoneinfo":"UTC"}"""), service, authorized = false)
        yield assertTrue(response.status == Status.Unauthorized, service.setZoneinfo.calls.isEmpty)
      },
      test("a call with no administrator behind it stores nothing") {
        val service = stub[UserService]
        for response <- run(patch(path, """{"zoneinfo":"UTC"}"""), service)
        yield assertTrue(response.status == Status.Unauthorized, service.setZoneinfo.calls.isEmpty)
      },
      test("a user named in the body or the query is not the subject: only the actor is changed") {
        val service = stub[UserService]
        for
          _ <- service.setZoneinfo.succeedsWith(())
          _ <- run(patch(asAdmin().addQueryParam("id", other.toString), s"""{"id":"$other","userId":"$other","zoneinfo":"UTC"}"""), service)
        yield assertTrue(service.setZoneinfo.calls == List((admin, Some("UTC"))))
      },
    ),
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

package versola.central.antifraud

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.resources.ResourceService
import versola.central.users.AuthClient
import versola.central.{TestAdminAuth, TestCentralConfig}
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

object AntifraudListControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val base = URL.empty / "antifraud" / "lists"

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val answered = AuthClient.Forwarded(Status.Ok, Some(Header.ContentType(MediaType.application.json)), "[]")

  /** What edge adds from the verified token. */
  private def asAdmin(request: Request, subject: String, tenant: String): Request =
    request.addHeader(AntifraudListController.SubjectHeader, subject).addHeader(AntifraudListController.TenantHeader, tenant)

  private def run(request: Request, auth: AuthClient, authorized: Boolean = true) =
    for
      client <- ZIO.service[Client]
      resources = stub[ResourceService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudListController.routes.provideEnvironment(ZEnvironment[AuthClient](auth) ++ ZEnvironment[ResourceService](resources) ++ tracing),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private def json(request: Request) = request.addHeader(Header.ContentType(MediaType.application.json))

  def spec = suite("central AntifraudListController")(
    test("without the central credentials every endpoint is 401 and auth is not called") {
      val auth = stub[AuthClient]
      for
        statuses <- ZIO.foreach(
          List(
            Request.get(base),
            Request.post(base, Body.fromString("{}")),
            Request.delete(base),
            Request.get(base / "audit"),
            Request.post(base / "preview", Body.fromString("{}")),
          ),
        )(r => run(asAdmin(r, "alice", "default"), auth, authorized = false).map(_.status))
      yield assertTrue(statuses.forall(_ == Status.Unauthorized), auth.forwardAntifraudLists.calls.isEmpty)
    },
    test("a request that carries no admin identity is 403, whatever else it has") {
      val auth = stub[AuthClient]
      for
        none <- run(Request.get(base), auth)
        onlySubject <- run(Request.get(base).addHeader(AntifraudListController.SubjectHeader, "alice"), auth)
        onlyTenant <- run(Request.get(base).addHeader(AntifraudListController.TenantHeader, "default"), auth)
      yield assertTrue(
        none.status == Status.Forbidden,
        onlySubject.status == Status.Forbidden,
        onlyTenant.status == Status.Forbidden,
        auth.forwardAntifraudLists.calls.isEmpty,
      )
    },
    test("an admin of the default tenant is an operator: no tenant goes to auth with the name") {
      val auth = stub[AuthClient]
      val entry = Json.Obj("listType" -> Json.Str("deny"), "valueType" -> Json.Str("ip"), "value" -> Json.Str("10.0.0.1"))
      for
        _ <- auth.forwardAntifraudLists.succeedsWith(AuthClient.Forwarded(Status.Created, None, "{}"))
        response <- run(asAdmin(json(Request.post(base, Body.fromString(entry.toJson))), "alice", "default"), auth)
        (method, path, query, body) = auth.forwardAntifraudLists.calls.head
      yield assertTrue(
        response.status == Status.Created,
        method == Method.POST,
        path == Nil,
        query.isEmpty,
        body == Some(Json.Obj("entry" -> entry, "actor" -> Json.Obj("name" -> Json.Str("alice")))),
      )
    },
    test("an admin of another tenant is limited to it, and cannot send an actor of their own") {
      val auth = stub[AuthClient]
      val entry = Json.Obj("listType" -> Json.Str("deny"), "valueType" -> Json.Str("ip"), "value" -> Json.Str("10.0.0.1"))
      val forged = Json.Obj("entry" -> entry, "actor" -> Json.Obj("name" -> Json.Str("root")))
      for
        _ <- auth.forwardAntifraudLists.succeedsWith(answered)
        _ <- run(asAdmin(json(Request.post(base, Body.fromString(forged.toJson))), "bob", "tenant-a"), auth)
        _ <- run(asAdmin(Request.get(base.addQueryParams(QueryParams("actor" -> "root", "actorTenantId" -> "default"))), "bob", "tenant-a"), auth)
        body = auth.forwardAntifraudLists.calls.head._4
        query = auth.forwardAntifraudLists.calls(1)._3
      yield assertTrue(
        body == Some(Json.Obj("entry" -> forged, "actor" -> Json.Obj("name" -> Json.Str("bob"), "tenantId" -> Json.Str("tenant-a")))),
        query.queryParam("actor").contains("bob"),
        query.queryParam("actorTenantId").contains("tenant-a"),
        query.map("actor").size == 1,
      )
    },
    test("list passes the filter and the page on, and drops what the endpoint does not take") {
      val auth = stub[AuthClient]
      val url = base.addQueryParams(QueryParams("listType" -> "deny", "global" -> "true", "limit" -> "5", "x" -> "y"))
      for
        _ <- auth.forwardAntifraudLists.succeedsWith(answered)
        response <- run(asAdmin(Request.get(url), "alice", "default"), auth)
        body <- response.body.asString
        (method, _, query, _) = auth.forwardAntifraudLists.calls.head
      yield assertTrue(
        response.status == Status.Ok,
        body == "[]",
        method == Method.GET,
        query.queryParam("listType").contains("deny"),
        query.queryParam("global").contains("true"),
        query.queryParam("limit").contains("5"),
        query.queryParam("x").isEmpty,
        query.queryParam("actor").contains("alice"),
        query.queryParam("actorTenantId").isEmpty,
      )
    },
    test("delete and audit go to their paths with their parameters") {
      val auth = stub[AuthClient]
      for
        _ <- auth.forwardAntifraudLists.succeedsWith(AuthClient.Forwarded(Status.NoContent, None, ""))
        deleted <- run(asAdmin(Request.delete(base.addQueryParam("id", "abc")), "alice", "default"), auth)
        _ <- run(
          asAdmin(Request.get((base / "audit").addQueryParams(QueryParams("entryId" -> "abc", "limit" -> "3", "id" -> "no"))), "alice", "default"),
          auth,
        )
        _ <- run(asAdmin(json(Request.post(base / "preview", Body.fromString("{}"))), "alice", "default"), auth)
        calls = auth.forwardAntifraudLists.calls
      yield assertTrue(
        deleted.status == Status.NoContent,
        calls(0)._1 == Method.DELETE && calls(0)._3.queryParam("id").contains("abc"),
        calls(1)._2 == List("audit") && calls(1)._3.queryParam("entryId").contains("abc") && calls(1)._3.queryParam("id").isEmpty,
        calls(2)._2 == List("preview"),
      )
    },
    test("auth's 400, 403 and 404 reach the caller with their body and content type") {
      val auth = stub[AuthClient]
      for
        _ <- auth.forwardAntifraudLists.succeedsWith(AuthClient.Forwarded(
          Status.BadRequest,
          Some(Header.ContentType(MediaType.text.plain)),
          "not an IP address",
        ))
        bad <- run(asAdmin(Request.get(base), "alice", "default"), auth)
        badBody <- bad.body.asString
        _ <- auth.forwardAntifraudLists.succeedsWith(AuthClient.Forwarded(Status.Forbidden, None, "only an operator"))
        forbidden <- run(asAdmin(Request.get(base), "alice", "default"), auth)
        _ <- auth.forwardAntifraudLists.succeedsWith(AuthClient.Forwarded(Status.NotFound, None, ""))
        missing <- run(asAdmin(Request.delete(base.addQueryParam("id", "x")), "alice", "default"), auth)
      yield assertTrue(
        bad.status == Status.BadRequest,
        badBody == "not an IP address",
        bad.header(Header.ContentType).exists(_.mediaType == MediaType.text.plain),
        forbidden.status == Status.Forbidden,
        missing.status == Status.NotFound,
      )
    },
    test("a body that is not a JSON object is 400 and auth is not called") {
      val auth = stub[AuthClient]
      for
        notJson <- run(asAdmin(Request.post(base, Body.fromString("{")), "alice", "default"), auth)
        array <- run(asAdmin(Request.post(base, Body.fromString("[]")), "alice", "default"), auth)
      yield assertTrue(notJson.status == Status.BadRequest, array.status == Status.BadRequest, auth.forwardAntifraudLists.calls.isEmpty)
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

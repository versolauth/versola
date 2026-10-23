package versola.central.antifraud

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.TestAdminAuth
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.util.http.Observability
import versola.util.{PageLimit, PageOffset}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.Instant
import java.util.UUID

object AntifraudListControllerSpec extends ZIOSpecDefault, ZIOStubs:
  private val base = URL.empty / "antifraud" / "lists"
  private val tenant = TenantId("tenant-a")
  private val entryId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
  private val operator = AdminCaller(subject = Some("alice"), tenant = Some(TenantId("default")))

  private val entry = ListEntryResponse(
    id = entryId,
    listType = AntifraudListType.deny,
    valueType = AntifraudValueType.prefix,
    value = "4915",
    tenantId = Some(tenant),
    expiresAt = Some(Instant.parse("2026-12-01T00:00:00Z")),
    reason = Some("sms pumping"),
    source = "manual",
    shadow = false,
    createdBy = "alice",
    createdAt = Instant.parse("2026-10-01T00:00:00Z"),
    hitCount = 0,
    lastHitAt = None,
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** What edge does to the request: the caller as query parameters of a `GET`, as body fields of the rest. */
  private def asAdmin(request: Request, subject: String, tenant: String): Task[Request] =
    if request.method == Method.GET then
      ZIO.succeed(
        request.updateURL: url =>
          url.setQueryParams(
            url.queryParams.removeQueryParam("adminSubject").removeQueryParam("adminTenant")
              .addQueryParam("adminSubject", subject).addQueryParam("adminTenant", tenant),
          ),
      )
    else
      request.body.asString.map: raw =>
        val fields = raw.fromJson[Json.Obj].toOption.fold(Chunk.empty[(String, Json)])(_.fields)
          .filterNot((name, _) => name == "adminSubject" || name == "adminTenant")
        val body = Json.Obj(fields ++ Chunk("adminSubject" -> Json.Str(subject), "adminTenant" -> Json.Str(tenant)))
        request.copy(body = Body.fromString(body.toJson))

  private def run(request: Request, service: AntifraudListService, authorized: Boolean = true) =
    for
      client <- ZIO.service[Client]
      resources = stub[ResourceService]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudListController.routes.provideEnvironment(
            ZEnvironment[AntifraudListService](service) ++ ZEnvironment[ResourceService](resources) ++ tracing,
          ),
        ),
      )
      _ <- resources.verifySecret.succeedsWith(true)
      response <- client.batched(if authorized then request.addHeader(TestAdminAuth.basicAuthHeader) else request)
    yield response

  private def runAs(request: Request, subject: String, tenant: String, service: AntifraudListService) =
    asAdmin(request, subject, tenant).flatMap(run(_, service))

  private val entryBody = """{"listType":"deny","valueType":"prefix","value":"4915","tenantId":"tenant-a","reason":"sms pumping"}"""
  private def post(url: URL, body: String = entryBody) = Request.post(url, Body.fromString(body))
  private def delete(body: String) = Request.delete(base).withBody(Body.fromString(body))

  def spec = suite("central AntifraudListController")(
    test("without the central credentials every endpoint is 401 and the service is not called") {
      val service = stub[AntifraudListService]
      for
        statuses <- ZIO.foreach(List(Request.get(base), post(base), delete("""{"id":"x"}"""), Request.get(base / "audit"), post(base / "preview"))):
          r =>
            asAdmin(r, "alice", "default").flatMap(run(_, service, authorized = false)).map(_.status)
      yield assertTrue(
        statuses.forall(_ == Status.Unauthorized),
        service.create.calls.isEmpty,
        service.list.calls.isEmpty,
        service.delete.calls.isEmpty,
        service.audit.calls.isEmpty,
        service.preview.calls.isEmpty,
      )
    },
    test("a request is handed to the service with the caller edge added, and the entry as the admin sent it") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.succeedsWith(entry)
        response <- runAs(post(base), "alice", "default", service)
        body <- response.body.asString
        (sent, caller) = service.create.calls.head
      yield assertTrue(
        response.status == Status.Created,
        body.fromJson[ListEntryResponse] == Right(entry),
        caller == operator,
        sent.valueType == AntifraudValueType.prefix,
        sent.value.contains("4915"),
        sent.tenantId.contains(tenant),
        sent.source.isEmpty,
        sent.shadow.isEmpty,
      )
    },
    test("a request without the caller reaches the service with none, and the service's refusal is 403") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.failsWith(AntifraudListError.NoCaller)
        response <- run(post(base), service)
        _ <- service.delete.failsWith(AntifraudListError.NoCaller)
        deleted <- run(delete("""{"id":"00000000-0000-0000-0000-0000000000e1"}"""), service)
        _ <- service.list.failsWith(AntifraudListError.NoCaller)
        listed <- run(Request.get(base), service)
      yield assertTrue(
        response.status == Status.Forbidden,
        deleted.status == Status.Forbidden,
        listed.status == Status.Forbidden,
        service.create.calls.head._2 == AdminCaller(subject = None, tenant = None),
        service.list.calls.head._2 == AdminCaller(subject = None, tenant = None),
      )
    },
    test("an admin of another tenant is passed on as that tenant, and a client-chosen caller is not what edge left") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.succeedsWith(entry)
        _ <- runAs(post(base, entryBody.dropRight(1) + ""","adminSubject":"root","adminTenant":"default"}"""), "bob", "tenant-a", service)
      yield assertTrue(service.create.calls.head._2 == AdminCaller(subject = Some("bob"), tenant = Some(tenant)))
    },
    test("auth's refusals become 400 with the reason, and 403") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.failsWith(AntifraudListError.Invalid("not an IP address"))
        invalid <- runAs(post(base), "alice", "default", service)
        reason <- invalid.body.asString
        _ <- service.create.failsWith(AntifraudListError.Forbidden("only an operator may change a global entry"))
        forbidden <- runAs(post(base), "alice", "default", service)
        _ <- service.preview.failsWith(AntifraudListError.Forbidden("not an entry of the actor's tenant"))
        previewForbidden <- runAs(post(base / "preview"), "alice", "default", service)
      yield assertTrue(
        invalid.status == Status.BadRequest,
        reason == "not an IP address",
        forbidden.status == Status.Forbidden,
        previewForbidden.status == Status.Forbidden,
      )
    },
    test("a malformed body, an unknown list type, and a body that is no entry are 400 and reach nothing") {
      val service = stub[AntifraudListService]
      for
        malformed <- run(post(base, "{"), service)
        unknown <- run(post(base, """{"listType":"nope","valueType":"ip","value":"1.1.1.1"}"""), service)
        notAnObject <- run(post(base, "[]"), service)
        noId <- run(delete("""{"adminSubject":"alice","adminTenant":"default"}"""), service)
        badId <- run(delete("""{"id":"nope"}"""), service)
      yield assertTrue(
        malformed.status == Status.BadRequest,
        unknown.status == Status.BadRequest,
        notAnObject.status == Status.BadRequest,
        noId.status == Status.BadRequest,
        badId.status == Status.BadRequest,
        service.create.calls.isEmpty,
        service.delete.calls.isEmpty,
      )
    },
    test("preview answers the counts") {
      val service = stub[AntifraudListService]
      for
        _ <- service.preview.succeedsWith(PreviewResponse(matched = Some(3), total = 1000))
        response <- runAs(post(base / "preview"), "alice", "default", service)
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[PreviewResponse] == Right(PreviewResponse(matched = Some(3), total = 1000)),
        service.preview.calls.head._2 == operator,
      )
    },
    test("list reads its filter and page from the query, and answers the entries") {
      val service = stub[AntifraudListService]
      val url = base.addQueryParams(
        QueryParams("listType" -> "deny", "valueType" -> "prefix", "tenantId" -> "tenant-a", "global" -> "false", "limit" -> "10", "offset" -> "20"),
      )
      for
        _ <- service.list.succeedsWith(ListEntriesResponse(List(entry)))
        response <- runAs(Request.get(url), "alice", "default", service)
        body <- response.body.asString
        (query, caller) = service.list.calls.head
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[ListEntriesResponse] == Right(ListEntriesResponse(List(entry))),
        query == ListEntriesQuery(
          listType = Some(AntifraudListType.deny),
          valueType = Some(AntifraudValueType.prefix),
          tenantId = Some(tenant),
          global = Some(false),
          limit = Some(PageLimit(10)),
          offset = Some(PageOffset(20)),
        ),
        caller == operator,
      )
    },
    test("list with nothing asked passes nothing on; an unknown value or a non-number is 400") {
      val service = stub[AntifraudListService]
      def get(first: (String, String)) = runAs(Request.get(base.addQueryParams(QueryParams(first))), "alice", "default", service)
      for
        _ <- service.list.succeedsWith(ListEntriesResponse(Nil))
        none <- runAs(Request.get(base), "alice", "default", service)
        badType <- get("listType" -> "nope")
        badLimit <- get("limit" -> "many")
        badTenant <- get("tenantId" -> "Not A Tenant")
        zero <- get("limit" -> "0")
        huge <- get("limit" -> "501")
        negative <- get("offset" -> "-1")
      yield assertTrue(
        none.status == Status.Ok,
        service.list.calls.head._1 == ListEntriesQuery(
          listType = None,
          valueType = None,
          tenantId = None,
          global = None,
          limit = None,
          offset = None,
        ),
        badType.status == Status.BadRequest,
        badLimit.status == Status.BadRequest,
        badTenant.status == Status.BadRequest,
        List(zero, huge, negative).forall(_.status == Status.BadRequest),
        service.list.calls.size == 1,
      )
    },
    test("delete takes the entry from the body: 204, and 404 for one that is not there") {
      val service = stub[AntifraudListService]
      val body = """{"id":"00000000-0000-0000-0000-0000000000e1"}"""
      for
        _ <- service.delete.succeedsWith(())
        gone <- runAs(delete(body), "alice", "default", service)
        _ <- service.delete.failsWith(AntifraudListError.NotFound)
        missing <- runAs(delete(body), "alice", "default", service)
      yield assertTrue(
        gone.status == Status.NoContent,
        missing.status == Status.NotFound,
        service.delete.calls.head == (entryId, operator),
      )
    },
    test("audit reads the entry and the page from the query and answers the records; a refusal is 403") {
      val service = stub[AntifraudListService]
      val record = AuditRecordResponse(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
        at = Instant.parse("2026-10-01T00:00:00Z"),
        action = AntifraudListAction.create,
        actor = "alice",
        entryId = entryId,
        listType = AntifraudListType.deny,
        valueType = AntifraudValueType.prefix,
        value = "4915",
        tenantId = Some(tenant),
        expiresAt = None,
        reason = None,
      )
      for
        _ <- service.audit.succeedsWith(AuditResponse(List(record)))
        ok <-
          runAs(Request.get((base / "audit").addQueryParams(QueryParams("entryId" -> entryId.toString, "limit" -> "5"))), "alice", "default", service)
        body <- ok.body.asString
        _ <- service.audit.failsWith(AntifraudListError.Forbidden("only an operator reads the audit"))
        forbidden <- runAs(Request.get(base / "audit"), "bob", "tenant-a", service)
        badId <- runAs(Request.get((base / "audit").addQueryParam("entryId", "nope")), "alice", "default", service)
      yield assertTrue(
        ok.status == Status.Ok,
        body.fromJson[AuditResponse] == Right(AuditResponse(List(record))),
        service.audit.calls.head == (Some(entryId), Some(PageLimit(5)), operator),
        forbidden.status == Status.Forbidden,
        badId.status == Status.BadRequest,
      )
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

package versola.central.antifraud

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.central.TestAdminAuth
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
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
  private val admin = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))
  private val attacker = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000f2"))

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
    createdBy = admin.toString,
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

  /** What edge does to the request: `userId` as a query parameter of a `GET`, as a body field of the rest, replacing what the client sent. */
  private def asAdmin(request: Request, userId: UserId): Task[Request] =
    if request.method == Method.GET then
      ZIO.succeed(request.updateURL(url => url.setQueryParams(url.queryParams.removeQueryParam("userId").addQueryParam("userId", userId.toString))))
    else
      request.body.asString.map: raw =>
        val fields = raw.fromJson[Json.Obj].toOption.fold(Chunk.empty[(String, Json)])(_.fields).filterNot((name, _) => name == "userId")
        request.copy(body = Body.fromString(Json.Obj(fields :+ ("userId" -> Json.Str(userId.toString))).toJson))

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

  private def runAs(request: Request, service: AntifraudListService, userId: UserId = admin) =
    asAdmin(request, userId).flatMap(run(_, service))

  private val entryBody = """{"listType":"deny","valueType":"prefix","value":"4915","tenantId":"tenant-a","reason":"sms pumping"}"""
  private def post(url: URL, body: String = entryBody) = Request.post(url, Body.fromString(body))
  private def delete(body: String) = Request.delete(base).withBody(Body.fromString(body))

  def spec = suite("central AntifraudListController")(
    test("without the central credentials every endpoint is 401 and the service is not called") {
      val service = stub[AntifraudListService]
      for
        statuses <- ZIO.foreach(List(Request.get(base), post(base), delete("""{"id":"x"}"""), Request.get(base / "audit"), post(base / "preview"))):
          r =>
            asAdmin(r, admin).flatMap(run(_, service, authorized = false)).map(_.status)
      yield assertTrue(
        statuses.forall(_ == Status.Unauthorized),
        service.create.calls.isEmpty,
        service.list.calls.isEmpty,
        service.delete.calls.isEmpty,
        service.audit.calls.isEmpty,
        service.preview.calls.isEmpty,
      )
    },
    test("an entry is handed to the service with the userId edge added, and as the admin sent it") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.succeedsWith(entry)
        response <- runAs(post(base), service)
        body <- response.body.asString
        sent = service.create.calls.head
      yield assertTrue(
        response.status == Status.Created,
        body.fromJson[ListEntryResponse] == Right(entry),
        sent.userId == admin,
        sent.valueType == AntifraudValueType.prefix,
        sent.value.contains("4915"),
        sent.tenantId.contains(tenant),
        sent.source.isEmpty,
        sent.shadow.isEmpty,
      )
    },
    test("a userId the client sent is what edge replaces: the service sees the token's") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.succeedsWith(entry)
        _ <- runAs(post(base, entryBody.dropRight(1) + s""","userId":"$attacker"}"""), service)
      yield assertTrue(service.create.calls.head.userId == admin)
    },
    test("a change without a userId, or with one that is not a UUID, is 400 and reaches nothing") {
      val service = stub[AntifraudListService]
      for
        noUser <- run(post(base), service)
        badUser <- run(post(base, entryBody.dropRight(1) + ""","userId":"nope"}"""), service)
        deleteNoUser <- run(delete("""{"id":"00000000-0000-0000-0000-0000000000e1"}"""), service)
        previewNoUser <- run(post(base / "preview"), service)
      yield assertTrue(
        List(noUser, badUser, deleteNoUser, previewNoUser).forall(_.status == Status.BadRequest),
        service.create.calls.isEmpty,
        service.list.calls.isEmpty,
        service.audit.calls.isEmpty,
        service.delete.calls.isEmpty,
        service.preview.calls.isEmpty,
      )
    },
    test("auth's refusal of an entry becomes 400 with the reason") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.failsWith(AntifraudListError.Invalid("not an IP address"))
        invalid <- runAs(post(base), service)
        reason <- invalid.body.asString
      yield assertTrue(invalid.status == Status.BadRequest, reason == "not an IP address")
    },
    test("a malformed body, an unknown list type, and a body that is no entry are 400 and reach nothing") {
      val service = stub[AntifraudListService]
      for
        malformed <- run(post(base, "{"), service)
        unknown <- run(post(base, """{"listType":"nope","valueType":"ip","value":"1.1.1.1"}"""), service)
        notAnObject <- run(post(base, "[]"), service)
        noId <- runAs(delete("{}"), service)
        badId <- runAs(delete("""{"id":"nope"}"""), service)
      yield assertTrue(
        List(malformed, unknown, notAnObject, noId, badId).forall(_.status == Status.BadRequest),
        service.create.calls.isEmpty,
        service.delete.calls.isEmpty,
      )
    },
    test("preview answers the counts") {
      val service = stub[AntifraudListService]
      for
        _ <- service.preview.succeedsWith(PreviewResponse(matched = Some(3), total = 1000))
        response <- runAs(post(base / "preview"), service)
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[PreviewResponse] == Right(PreviewResponse(matched = Some(3), total = 1000)),
        service.preview.calls.head.userId == admin,
      )
    },
    test("list reads its filter and page from the query, and answers the entries") {
      val service = stub[AntifraudListService]
      val url = base.addQueryParams(
        QueryParams("listType" -> "deny", "valueType" -> "prefix", "tenantId" -> "tenant-a", "global" -> "false", "limit" -> "10", "offset" -> "20"),
      )
      for
        _ <- service.list.succeedsWith(ListEntriesResponse(List(entry)))
        response <- run(Request.get(url), service)
        body <- response.body.asString
        query = service.list.calls.head
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
      )
    },
    test("list with nothing asked passes nothing on; an unknown value, a non-number or a page out of range is 400") {
      val service = stub[AntifraudListService]
      def get(first: (String, String)) = run(Request.get(base.addQueryParams(QueryParams(first))), service)
      for
        _ <- service.list.succeedsWith(ListEntriesResponse(Nil))
        none <- run(Request.get(base), service)
        badType <- get("listType" -> "nope")
        badLimit <- get("limit" -> "many")
        badTenant <- get("tenantId" -> "Not A Tenant")
        zero <- get("limit" -> "0")
        huge <- get("limit" -> "501")
        negative <- get("offset" -> "-1")
      yield assertTrue(
        none.status == Status.Ok,
        service.list.calls.head == ListEntriesQuery(
          listType = None,
          valueType = None,
          tenantId = None,
          global = None,
          limit = None,
          offset = None,
        ),
        List(badType, badLimit, badTenant, zero, huge, negative).forall(_.status == Status.BadRequest),
        service.list.calls.size == 1,
      )
    },
    test("delete takes the entry and the userId from the body: 204, and 404 for one that is not there") {
      val service = stub[AntifraudListService]
      val body = """{"id":"00000000-0000-0000-0000-0000000000e1"}"""
      for
        _ <- service.delete.succeedsWith(())
        gone <- runAs(delete(body), service)
        _ <- service.delete.failsWith(AntifraudListError.NotFound)
        missing <- runAs(delete(body), service)
      yield assertTrue(
        gone.status == Status.NoContent,
        missing.status == Status.NotFound,
        service.delete.calls.head == DeleteListEntryRequest(id = entryId, userId = admin),
      )
    },
    test("audit reads the entry and the page from the query and answers the records") {
      val service = stub[AntifraudListService]
      val record = AuditRecordResponse(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
        at = Instant.parse("2026-10-01T00:00:00Z"),
        action = AntifraudListAction.create,
        actor = admin.toString,
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
        ok <- run(Request.get((base / "audit").addQueryParams(QueryParams("entryId" -> entryId.toString, "limit" -> "5"))), service)
        body <- ok.body.asString
        badId <- run(Request.get((base / "audit").addQueryParam("entryId", "nope")), service)
      yield assertTrue(
        ok.status == Status.Ok,
        body.fromJson[AuditResponse] == Right(AuditResponse(List(record))),
        service.audit.calls.head == (Some(entryId), Some(PageLimit(5))),
        badId.status == Status.BadRequest,
      )
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

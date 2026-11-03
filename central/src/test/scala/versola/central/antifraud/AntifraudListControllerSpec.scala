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

  /** What edge does to the request, replacing what the client sent. On a tenant route: `callerTenantId` (the token's tenant) as
    * a query parameter of a `GET` and a body field of the rest; and `userId` on a change, a body field. An operator route
    * (`/antifraud/global/...`) gets no `callerTenantId`. */
  private def asAdmin(request: Request, userId: UserId, callerTenant: Option[TenantId] = Some(tenant)): Task[Request] =
    val tenantRoute = !request.url.path.segments.contains("global")
    val callerTenantId = callerTenant.filter(_ => tenantRoute)
    if request.method == Method.GET then
      ZIO.succeed(request.updateURL(url =>
        val cleaned = url.queryParams.removeQueryParam("callerTenantId")
        url.setQueryParams(callerTenantId.fold(cleaned)(t => cleaned.addQueryParam("callerTenantId", t.toString))),
      ))
    else
      request.body.asString.map: raw =>
        val fields = raw.fromJson[Json.Obj].toOption.fold(Chunk.empty[(String, Json)])(_.fields)
          .filterNot((name, _) => name == "userId" || name == "callerTenantId")
        val injected = (fields :+ ("userId" -> Json.Str(userId.toString))) ++ callerTenantId.map(t => "callerTenantId" -> Json.Str(t.toString))
        request.copy(body = Body.fromString(Json.Obj(injected).toJson))

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

  private def runAs(request: Request, service: AntifraudListService, userId: UserId = admin, callerTenant: Option[TenantId] = Some(tenant)) =
    asAdmin(request, userId, callerTenant).flatMap(run(_, service))

  private val entryBody = """{"listType":"deny","valueType":"prefix","value":"4915","tenantId":"tenant-a","reason":"sms pumping"}"""
  private def post(url: URL, body: String = entryBody) = Request.post(url, Body.fromString(body))
  private def delete(body: String, url: URL = base) = Request.delete(url).withBody(Body.fromString(body))
  private val global = URL.empty / "antifraud" / "global" / "lists"

  def spec = suite("central AntifraudListController")(
    test("without the central credentials every endpoint is 401 and the service is not called") {
      val service = stub[AntifraudListService]
      for
        statuses <- ZIO.foreach(
          List(
            Request.get(base),
            post(base),
            delete("""{"id":"x"}"""),
            Request.get(base / "audit"),
            post(base / "preview"),
            Request.get(global),
            post(global),
            delete("""{"id":"x"}""", global),
            Request.get(global / "audit"),
            post(global / "preview"),
          ),
        ): r =>
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
        (sent, caller) = service.create.calls.head
      yield assertTrue(
        response.status == Status.Created,
        body.fromJson[ListEntryResponse] == Right(entry),
        caller == AntifraudCaller.Tenant(tenant),
        sent.userId == admin,
        sent.valueType == AntifraudValueType.prefix,
        sent.value.contains("4915"),
        sent.tenantId.contains(tenant),
        sent.shadow.isEmpty,
      )
    },
    test("a userId the client sent is what edge replaces: the service sees the token's") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.succeedsWith(entry)
        _ <- runAs(post(base, entryBody.dropRight(1) + s""","userId":"$attacker"}"""), service)
      yield assertTrue(service.create.calls.head._1.userId == admin)
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
        service.preview.calls.head._1.userId == admin,
        service.preview.calls.head._2 == AntifraudCaller.Tenant(tenant),
      )
    },
    test("list reads its filter and page from the query, and answers the entries") {
      val service = stub[AntifraudListService]
      val url = base.addQueryParams(
        QueryParams("listType" -> "deny", "valueType" -> "prefix", "tenantId" -> "tenant-a", "global" -> "false", "limit" -> "10", "offset" -> "20"),
      )
      for
        _ <- service.list.succeedsWith(ListEntriesResponse(List(entry)))
        response <- runAs(Request.get(url), service)
        body <- response.body.asString
        (query, caller) = service.list.calls.head
      yield assertTrue(
        caller == AntifraudCaller.Tenant(tenant),
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
      def get(first: (String, String)) = runAs(Request.get(base.addQueryParams(QueryParams(first))), service)
      for
        _ <- service.list.succeedsWith(ListEntriesResponse(Nil))
        none <- runAs(Request.get(base), service)
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
        service.delete.calls.head == (
          DeleteListEntryRequest(id = entryId, userId = admin, callerTenantId = Some(tenant)),
          AntifraudCaller.Tenant(tenant),
        ),
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
        previous = None,
      )
      for
        _ <- service.audit.succeedsWith(AuditResponse(List(record)))
        ok <- runAs(Request.get((base / "audit").addQueryParams(QueryParams("entryId" -> entryId.toString, "limit" -> "5"))), service)
        body <- ok.body.asString
        badId <- runAs(Request.get((base / "audit").addQueryParam("entryId", "nope")), service)
      yield assertTrue(
        ok.status == Status.Ok,
        body.fromJson[AuditResponse] == Right(AuditResponse(List(record))),
        service.audit.calls.head == (Some(entryId), Some(PageLimit(5)), AntifraudCaller.Tenant(tenant)),
        badId.status == Status.BadRequest,
      )
    },
    test("a tenant route is a tenant caller's: the tenant is the one edge put in from the token, whatever the body or the query says") {
      val service = stub[AntifraudListService]
      val forged = entryBody.dropRight(1) + ""","callerTenantId":"tenant-b"}"""
      for
        _ <- service.create.succeedsWith(entry)
        _ <- service.list.succeedsWith(ListEntriesResponse(Nil))
        _ <- service.audit.succeedsWith(AuditResponse(Nil))
        _ <- service.delete.succeedsWith(())
        _ <- service.preview.succeedsWith(PreviewResponse(None, 1))
        _ <- runAs(post(base, forged), service)
        _ <- runAs(post(base / "preview", forged), service)
        _ <- runAs(Request.get(base.addQueryParam("callerTenantId", "tenant-b")), service)
        _ <- runAs(Request.get((base / "audit").addQueryParam("callerTenantId", "tenant-b")), service)
        _ <- runAs(delete("""{"id":"00000000-0000-0000-0000-0000000000e1","callerTenantId":"tenant-b"}"""), service)
        mine = AntifraudCaller.Tenant(tenant)
      yield assertTrue(
        service.create.calls.map(_._2) == List(mine),
        service.preview.calls.map(_._2) == List(mine),
        service.list.calls.map(_._2) == List(mine),
        service.audit.calls.map(_._3) == List(mine),
        service.delete.calls.map(_._2) == List(mine),
      )
    },
    test("a tenant route without a callerTenantId is 400, never an operator's, and reaches nothing") {
      val service = stub[AntifraudListService]
      for
        create <- run(post(base, entryBody.dropRight(1) + s""","userId":"$admin"}"""), service)
        list <- run(Request.get(base), service)
        audit <- run(Request.get(base / "audit"), service)
        deleted <- run(delete(s"""{"id":"$entryId","userId":"$admin"}"""), service)
        preview <- run(post(base / "preview", entryBody.dropRight(1) + s""","userId":"$admin"}"""), service)
      yield assertTrue(
        List(create, list, audit, deleted, preview).forall(_.status == Status.BadRequest),
        service.create.calls.isEmpty,
        service.list.calls.isEmpty,
        service.audit.calls.isEmpty,
        service.delete.calls.isEmpty,
        service.preview.calls.isEmpty,
      )
    },
    test("the global routes are the operator's whatever the request names, and take no callerTenantId") {
      val service = stub[AntifraudListService]
      val withTenant = entryBody.dropRight(1) + ""","callerTenantId":"tenant-b"}"""
      for
        _ <- service.create.succeedsWith(entry)
        _ <- service.list.succeedsWith(ListEntriesResponse(Nil))
        _ <- service.audit.succeedsWith(AuditResponse(Nil))
        _ <- service.delete.succeedsWith(())
        _ <- service.preview.succeedsWith(PreviewResponse(None, 1))
        created <- runAs(post(global, withTenant), service)
        _ <- runAs(post(global / "preview", withTenant), service)
        _ <- runAs(Request.get(global.addQueryParam("callerTenantId", "tenant-b")), service)
        _ <- runAs(Request.get((global / "audit").addQueryParam("callerTenantId", "tenant-b")), service)
        _ <- runAs(delete(s"""{"id":"$entryId","callerTenantId":"tenant-b"}""", global), service)
        // and with no tenant at all, as edge sends it
        noTenant <- runAs(post(global), service, callerTenant = None)
      yield assertTrue(
        created.status == Status.Created,
        noTenant.status == Status.Created,
        service.create.calls.map(_._2) == List(AntifraudCaller.Operator, AntifraudCaller.Operator),
        service.preview.calls.map(_._2) == List(AntifraudCaller.Operator),
        service.list.calls.map(_._2) == List(AntifraudCaller.Operator),
        service.audit.calls.map(_._3) == List(AntifraudCaller.Operator),
        service.delete.calls.map(_._2) == List(AntifraudCaller.Operator),
        // the entry's own tenant is still the body's: it is the entry's, not the caller's
        service.create.calls.head._1.tenantId.contains(tenant),
      )
    },
    test("a change on a global route still needs the admin: no userId is 400") {
      val service = stub[AntifraudListService]
      for
        create <- run(post(global), service)
        removed <- run(delete(s"""{"id":"$entryId"}""", global), service)
      yield assertTrue(
        create.status == Status.BadRequest,
        removed.status == Status.BadRequest,
        service.create.calls.isEmpty,
        service.delete.calls.isEmpty,
      )
    },
    test("auth's 403 is 403 with its reason, on both families of routes") {
      val service = stub[AntifraudListService]
      for
        _ <- service.create.failsWith(AntifraudListError.Forbidden("a tenant admin adds entries of its own tenant only"))
        _ <- service.delete.failsWith(AntifraudListError.Forbidden("a tenant admin removes its own tenant's entries only"))
        _ <- service.list.failsWith(AntifraudListError.Forbidden("a tenant admin lists its own tenant's entries and the global ones"))
        create <- runAs(post(base), service)
        reason <- create.body.asString
        removed <- runAs(delete(s"""{"id":"$entryId"}"""), service)
        listed <- runAs(Request.get(base.addQueryParam("tenantId", "tenant-b")), service)
      yield assertTrue(
        create.status == Status.Forbidden,
        reason == "a tenant admin adds entries of its own tenant only",
        removed.status == Status.Forbidden,
        listed.status == Status.Forbidden,
      )
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

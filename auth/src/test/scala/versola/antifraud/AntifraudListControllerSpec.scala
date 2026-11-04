package versola.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.audit.AuthAuditKinds
import versola.auth.TestEnvConfig
import versola.oauth.client.model.TenantId
import versola.util.JWT
import versola.util.audit.AuditEvents
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object AntifraudListControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val config = TestEnvConfig.coreConfig
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
  private val tenant = TenantId("tenant-a")
  private val userId = "00000000-0000-0000-0000-0000000000f1"
  private val entryId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
  private val base = URL.empty / "antifraud" / "lists"

  private val stored = AntifraudListEntry(
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

  private def token(key: SecretKey) =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def authorized(request: Request, key: SecretKey = config.central.secretKey): Task[Request] =
    token(key).map(t => request.addHeader(Header.Authorization.Bearer(t)).addHeader(Header.ContentType(MediaType.application.json)))

  /** Runs `request` against the controller with the given collaborators. */
  private def call(
      lists: AntifraudLists,
      request: Request,
      key: SecretKey = config.central.secretKey,
  ): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudListController.routes.provideEnvironment(
            ZEnvironment(lists) ++ ZEnvironment(config) ++ ZEnvironment(RecordingAntifraudEvents()) ++ ZEnvironment[AuditEvents](AuditEvents.noop) ++ tracing,
          ),
        ),
      )
      signed <- authorized(request, key)
      response <- client.batched(signed)
    yield response

  /** The scope central says a call is made in: the operator's, or a tenant admin's of `tenant-a`. */
  private val operatorScope = """"scope":"operator""""
  private val tenantScope = """"scope":"tenant","callerTenantId":"tenant-a""""

  /** The entry as central sends it: the entry's fields, `userId` and the scope (the operator's unless said). */
  private def entryBody(entry: String, scope: String = operatorScope) =
    Body.fromString(entry.dropRight(1) + s""","userId":"$userId",$scope}""")

  private val prefixEntry = """{"listType":"deny","valueType":"prefix","value":"4915","tenantId":"tenant-a","reason":"sms pumping"}"""

  /** The query of a `GET` or a `DELETE`, in the operator's scope unless the parameters say otherwise. */
  private def withParams(first: (String, String), more: (String, String)*) =
    val all = ("scope" -> "operator") :: first :: more.toList
    base.addQueryParams(QueryParams(all.head, all.tail*))

  private def asTenant(first: (String, String)*) =
    base.addQueryParams(QueryParams("scope" -> "tenant", ("callerTenantId" -> "tenant-a") +: first*))

  def spec = suite("AntifraudListController")(
    test("a request without a token, or with one signed by another key, is 401 and reaches nothing") {
      val lists = stub[AntifraudLists]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            AntifraudListController.routes.provideEnvironment(
              ZEnvironment(lists) ++ ZEnvironment(config) ++ ZEnvironment(RecordingAntifraudEvents()) ++ ZEnvironment[AuditEvents](AuditEvents.noop) ++ tracing,
            ),
          ),
        )
        anonymous <- client.batched(Request.get(base))
        forged <- call(lists, Request.get(base), wrongKey)
      yield assertTrue(anonymous.status == Status.Unauthorized, forged.status == Status.Unauthorized, lists.list.calls.isEmpty)
    },
    test("POST creates the entry as the user central names and answers 201 with it") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.create.succeedsWith(stored)
        response <- call(lists, Request.post(base, entryBody(prefixEntry)))
        body <- response.body.asString
        (sent, actor, _) = lists.create.calls.head
      yield assertTrue(
        response.status == Status.Created,
        body.fromJson[AntifraudListEntry] == Right(stored),
        actor == userId,
        sent.valueType == AntifraudValueType.prefix,
        sent.source == "manual",
        !sent.shadow,
      )
    },
    test("the source is the server's: a body that names one is stored as manual all the same") {
      val lists = stub[AntifraudLists]
      val claimed = """{"listType":"deny","valueType":"prefix","value":"4915","source":"detector:prefix_anomaly","reason":"x"}"""
      for
        _ <- lists.create.succeedsWith(stored)
        _ <- lists.preview.succeedsWith(ListPreview(matched = None, total = 0))
        created <- call(lists, Request.post(base, entryBody(claimed)))
        previewed <- call(lists, Request.post(base / "preview", entryBody(claimed)))
      yield assertTrue(
        created.status == Status.Created,
        previewed.status == Status.Ok,
        lists.create.calls.head._1.source == ListEntrySource.Manual,
        lists.preview.calls.head.source == ListEntrySource.Manual,
      )
    },
    test("a phone is hashed the way an event's destination is, and stored as the hash") {
      val lists = stub[AntifraudLists]
      val entry = """{"listType":"deny","valueType":"phone_hash","phone":"+77011234567"}"""
      for
        _ <- lists.create.succeedsWith(stored)
        response <- call(lists, Request.post(base, entryBody(entry)))
      yield assertTrue(response.status == Status.Created, lists.create.calls.head._1.value == "hash:+77011234567")
    },
    test("a phone with a value, a phone for another kind of value, an invalid phone or neither are 400") {
      val lists = stub[AntifraudLists]
      def status(entry: String) = call(lists, Request.post(base, entryBody(entry))).map(_.status)
      for
        both <- status("""{"listType":"deny","valueType":"phone_hash","phone":"+77011234567","value":"x"}""")
        wrongKind <- status("""{"listType":"deny","valueType":"prefix","phone":"+77011234567"}""")
        invalid <- status("""{"listType":"deny","valueType":"phone_hash","phone":"12"}""")
        neither <- status("""{"listType":"deny","valueType":"prefix"}""")
      yield assertTrue(
        both == Status.BadRequest,
        wrongKind == Status.BadRequest,
        invalid == Status.BadRequest,
        neither == Status.BadRequest,
        lists.create.calls.isEmpty,
      )
    },
    test("a value the service refuses is 400 with its reason") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.create.failsWith(InvalidListEntry("not an IP address"))
        invalid <- call(lists, Request.post(base, entryBody(prefixEntry)))
        reason <- invalid.body.asString
      yield assertTrue(invalid.status == Status.BadRequest, reason == "not an IP address")
    },
    test("a malformed body, an unknown list type, and a missing or malformed userId on a change are 400") {
      val lists = stub[AntifraudLists]
      for
        malformed <- call(lists, Request.post(base, Body.fromString("{")))
        unknown <- call(lists, Request.post(base, entryBody("""{"listType":"nope","valueType":"ip","value":"1.1.1.1"}""")))
        noUserInBody <- call(lists, Request.post(base, Body.fromString(prefixEntry)))
        badUserInBody <- call(lists, Request.post(base, Body.fromString(prefixEntry.dropRight(1) + ""","userId":"nope"}""")))
        noUserOnDelete <- call(lists, Request.delete(withParams("id" -> entryId.toString)))
      yield assertTrue(
        List(malformed, unknown, noUserInBody, badUserInBody, noUserOnDelete).map(_.status) == List.fill(5)(Status.BadRequest),
        lists.create.calls.isEmpty,
        lists.list.calls.isEmpty,
        lists.delete.calls.isEmpty,
      )
    },
    test("POST preview answers the counts") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.preview.succeedsWith(ListPreview(matched = Some(3), total = 1000))
        ok <- call(lists, Request.post(base / "preview", entryBody(prefixEntry)))
        body <- ok.body.asString
      yield assertTrue(ok.status == Status.Ok, body.fromJson[ListPreview] == Right(ListPreview(matched = Some(3), total = 1000)))
    },
    test("GET lists with the filter and the page") {
      val lists = stub[AntifraudLists]
      val url = withParams("listType" -> "deny", "valueType" -> "prefix", "tenantId" -> "tenant-a", "limit" -> "10", "offset" -> "20")
      for
        _ <- lists.list.succeedsWith(List(stored))
        response <- call(lists, Request.get(url))
        body <- response.body.asString
        (filter, limit, offset) = lists.list.calls.head
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[List[AntifraudListEntry]] == Right(List(stored)),
        filter == AntifraudListFilter(
          listType = Some(AntifraudListType.deny),
          valueType = Some(AntifraudValueType.prefix),
          tenantId = Some(Some(tenant)),
        ),
        limit == 10,
        offset == 20,
      )
    },
    test(
      "GET lists: global=true asks for the global entries; with a tenant, an unknown value, a bad number or flag, or a page out of range it is 400",
    ) {
      val lists = stub[AntifraudLists]
      def get(first: (String, String), more: (String, String)*) = call(lists, Request.get(withParams(first, more*)))
      for
        _ <- lists.list.succeedsWith(Nil)
        global <- get("global" -> "true")
        both <- get("global" -> "true", "tenantId" -> "tenant-a")
        badType <- get("listType" -> "nope")
        notANumber <- get("limit" -> "many")
        notAFlag <- get("global" -> "maybe")
        zero <- get("limit" -> "0")
        huge <- get("limit" -> "501")
        negative <- get("offset" -> "-1")
      yield assertTrue(
        global.status == Status.Ok,
        lists.list.calls.head._1.tenantId == Some(None),
        lists.list.calls.head._3 == 0,
        lists.list.calls.head._2 == 100,
        both.status == Status.BadRequest,
        badType.status == Status.BadRequest,
        notANumber.status == Status.BadRequest,
        notAFlag.status == Status.BadRequest,
        List(zero, huge, negative).forall(_.status == Status.BadRequest),
        lists.list.calls.size == 1,
      )
    },
    test("DELETE answers 204 as the named user, 404 for an entry that is not there, 400 for a bad id") {
      val lists = stub[AntifraudLists]
      def delete(id: String) = call(lists, Request.delete(withParams("id" -> id, "userId" -> userId)))
      for
        _ <- lists.find.succeedsWith(Some(stored))
        _ <- lists.delete.succeedsWith(true)
        gone <- delete(entryId.toString)
        _ <- lists.delete.succeedsWith(false)
        raced <- delete(entryId.toString)
        _ <- lists.find.succeedsWith(None)
        missing <- delete(entryId.toString)
        malformed <- delete("nope")
      yield assertTrue(
        gone.status == Status.NoContent,
        raced.status == Status.NotFound,
        missing.status == Status.NotFound,
        malformed.status == Status.BadRequest,
        lists.delete.calls.size == 2,
        (lists.delete.calls.head._1, lists.delete.calls.head._2) == (entryId, userId),
        lists.delete.calls.head._3.kind == AuthAuditKinds.AntifraudListChanged,
      )
    },
    test("a call that does not say its scope is 400 and reaches nothing: a missing scope is never the operator's") {
      val lists = stub[AntifraudLists]
      val noScope = Body.fromString(prefixEntry.dropRight(1) + s""","userId":"$userId"}""")
      val tenantWithoutTenant = Body.fromString(prefixEntry.dropRight(1) + s""","userId":"$userId","scope":"tenant"}""")
      val unknownScope = Body.fromString(prefixEntry.dropRight(1) + s""","userId":"$userId","scope":"root"}""")
      for
        create <- call(lists, Request.post(base, noScope))
        preview <- call(lists, Request.post(base / "preview", noScope))
        tenantless <- call(lists, Request.post(base, tenantWithoutTenant))
        unknown <- call(lists, Request.post(base, unknownScope))
        list <- call(lists, Request.get(base))
        listTenantless <- call(lists, Request.get(base.addQueryParam("scope", "tenant")))
        delete <- call(lists, Request.delete(base.addQueryParams(QueryParams("id" -> entryId.toString, "userId" -> userId))))
        audit <- call(lists, Request.get(base / "audit"))
      yield assertTrue(
        List(create, preview, tenantless, unknown, list, listTenantless, delete, audit).map(_.status) == List.fill(8)(Status.BadRequest),
        lists.create.calls.isEmpty,
        lists.preview.calls.isEmpty,
        lists.list.calls.isEmpty,
        lists.find.calls.isEmpty,
        lists.delete.calls.isEmpty,
        lists.audit.calls.isEmpty,
      )
    },
    test("a tenant admin adds entries of its own tenant: one that names no tenant gets its own, another tenant's is 403") {
      val lists = stub[AntifraudLists]
      val other = """{"listType":"deny","valueType":"prefix","value":"4915","tenantId":"tenant-b"}"""
      val unnamed = """{"listType":"deny","valueType":"prefix","value":"4915"}"""
      for
        _ <- lists.create.succeedsWith(stored)
        own <- call(lists, Request.post(base, entryBody(prefixEntry, tenantScope)))
        defaulted <- call(lists, Request.post(base, entryBody(unnamed, tenantScope)))
        refused <- call(lists, Request.post(base, entryBody(other, tenantScope)))
        refusedPreview <- call(lists, Request.post(base / "preview", entryBody(other, tenantScope)))
      yield assertTrue(
        own.status == Status.Created,
        defaulted.status == Status.Created,
        lists.create.calls.map(_._1.tenantId) == List(Some(tenant), Some(tenant)),
        refused.status == Status.Forbidden,
        refusedPreview.status == Status.Forbidden,
        lists.create.calls.size == 2,
        lists.preview.calls.isEmpty,
      )
    },
    test("a tenant admin previews within its tenant") {
      val lists = stub[AntifraudLists]
      val unnamed = """{"listType":"deny","valueType":"prefix","value":"4915"}"""
      for
        _ <- lists.preview.succeedsWith(ListPreview(matched = Some(1), total = 10))
        ok <- call(lists, Request.post(base / "preview", entryBody(unnamed, tenantScope)))
      yield assertTrue(ok.status == Status.Ok, lists.preview.calls.map(_.tenantId) == List(Some(tenant)))
    },
    test("the operator adds a global entry, or one of any tenant") {
      val lists = stub[AntifraudLists]
      val global = """{"listType":"deny","valueType":"prefix","value":"4915"}"""
      for
        _ <- lists.create.succeedsWith(stored)
        everyone <- call(lists, Request.post(base, entryBody(global)))
        another <- call(lists, Request.post(base, entryBody(prefixEntry.replace("tenant-a", "tenant-b"))))
      yield assertTrue(
        everyone.status == Status.Created,
        another.status == Status.Created,
        lists.create.calls.map(_._1.tenantId) == List(None, Some(TenantId("tenant-b"))),
      )
    },
    test("a tenant admin lists the global entries and its tenant's, and may narrow to its own tenant or to the global ones, not to another's") {
      val lists = stub[AntifraudLists]
      def get(params: (String, String)*) = call(lists, Request.get(asTenant(params*)))
      for
        _ <- lists.list.succeedsWith(Nil)
        all <- get()
        own <- get("tenantId" -> "tenant-a")
        global <- get("global" -> "true")
        other <- get("tenantId" -> "tenant-b")
        calls = lists.list.calls.map(_._1)
      yield assertTrue(
        all.status == Status.Ok,
        own.status == Status.Ok,
        global.status == Status.Ok,
        other.status == Status.Forbidden,
        calls.size == 3,
        calls.head == AntifraudListFilter(None, None, None, visibleTo = Some(tenant)),
        calls(1).tenantId == Some(Some(tenant)),
        calls(2).tenantId == Some(None),
        calls.forall(_.visibleTo.contains(tenant)),
      )
    },
    test("the operator lists every tenant's entries, narrowed as it asks") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.list.succeedsWith(Nil)
        all <- call(lists, Request.get(withParams("limit" -> "5")))
        other <- call(lists, Request.get(withParams("tenantId" -> "tenant-b")))
        calls = lists.list.calls.map(_._1)
      yield assertTrue(
        all.status == Status.Ok,
        other.status == Status.Ok,
        calls.head.visibleTo.isEmpty,
        calls(1) == AntifraudListFilter(None, None, Some(Some(TenantId("tenant-b"))), visibleTo = None),
      )
    },
    test("a tenant admin removes its own tenant's entries: another tenant's or a global one is 403 and stays, a missing one is 404") {
      val lists = stub[AntifraudLists]
      def delete = call(lists, Request.delete(asTenant("id" -> entryId.toString, "userId" -> userId)))
      for
        _ <- lists.delete.succeedsWith(true)
        _ <- lists.find.succeedsWith(Some(stored))
        own <- delete
        _ <- lists.find.succeedsWith(Some(stored.copy(tenantId = Some(TenantId("tenant-b")))))
        other <- delete
        _ <- lists.find.succeedsWith(Some(stored.copy(tenantId = None)))
        global <- delete
        _ <- lists.find.succeedsWith(None)
        missing <- delete
      yield assertTrue(
        own.status == Status.NoContent,
        other.status == Status.Forbidden,
        global.status == Status.Forbidden,
        missing.status == Status.NotFound,
        lists.delete.calls.size == 1,
      )
    },
    test("the operator removes an entry of any tenant, and a global one") {
      val lists = stub[AntifraudLists]
      def delete = call(lists, Request.delete(withParams("id" -> entryId.toString, "userId" -> userId)))
      for
        _ <- lists.delete.succeedsWith(true)
        _ <- lists.find.succeedsWith(Some(stored.copy(tenantId = Some(TenantId("tenant-b")))))
        other <- delete
        _ <- lists.find.succeedsWith(Some(stored.copy(tenantId = None)))
        global <- delete
      yield assertTrue(other.status == Status.NoContent, global.status == Status.NoContent, lists.delete.calls.size == 2)
    },
    test("a tenant admin reads the audit of the entries it can see; the operator reads all of it") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.audit.succeedsWith(Nil)
        tenantRead <- call(lists, Request.get((base / "audit").addQueryParams(QueryParams("scope" -> "tenant", "callerTenantId" -> "tenant-a"))))
        operatorRead <- call(lists, Request.get((base / "audit").addQueryParams(QueryParams("scope" -> "operator"))))
      yield assertTrue(
        tenantRead.status == Status.Ok,
        operatorRead.status == Status.Ok,
        lists.audit.calls == List((None, 100, Some(tenant)), (None, 100, None)),
      )
    },
    test("GET audit answers the records") {
      val lists = stub[AntifraudLists]
      val record = AntifraudListAudit(
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
        previous = None,
      )
      for
        _ <- lists.audit.succeedsWith(List(record))
        ok <-
          call(lists, Request.get((base / "audit").addQueryParams(QueryParams("scope" -> "operator", "entryId" -> entryId.toString, "limit" -> "5"))))
        body <- ok.body.asString
        badId <- call(lists, Request.get((base / "audit").addQueryParams(QueryParams("scope" -> "operator", "entryId" -> "nope"))))
      yield assertTrue(
        ok.status == Status.Ok,
        body.fromJson[List[AntifraudListAudit]] == Right(List(record)),
        lists.audit.calls.head == (Some(entryId), 5, None),
        badId.status == Status.BadRequest,
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging

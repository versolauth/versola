package versola.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.model.TenantId
import versola.util.JWT
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
            ZEnvironment(lists) ++ ZEnvironment(config) ++ ZEnvironment(RecordingAntifraudEvents()) ++ tracing,
          ),
        ),
      )
      signed <- authorized(request, key)
      response <- client.batched(signed)
    yield response

  /** The entry as central sends it: the entry's fields and `userId`. */
  private def entryBody(entry: String) = Body.fromString(entry.dropRight(1) + s""","userId":"$userId"}""")

  private val prefixEntry = """{"listType":"deny","valueType":"prefix","value":"4915","tenantId":"tenant-a","reason":"sms pumping"}"""

  private def withUser(params: (String, String)*) =
    base.addQueryParams(QueryParams(("userId" -> userId), params*))

  def spec = suite("AntifraudListController")(
    test("a request without a token, or with one signed by another key, is 401 and reaches nothing") {
      val lists = stub[AntifraudLists]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            AntifraudListController.routes.provideEnvironment(
              ZEnvironment(lists) ++ ZEnvironment(config) ++ ZEnvironment(RecordingAntifraudEvents()) ++ tracing,
            ),
          ),
        )
        anonymous <- client.batched(Request.get(base))
        forged <- call(lists, Request.get(withUser()), wrongKey)
      yield assertTrue(anonymous.status == Status.Unauthorized, forged.status == Status.Unauthorized, lists.list.calls.isEmpty)
    },
    test("POST creates the entry as the user central names and answers 201 with it") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.create.succeedsWith(stored)
        response <- call(lists, Request.post(base, entryBody(prefixEntry)))
        body <- response.body.asString
        (sent, actor) = lists.create.calls.head
      yield assertTrue(
        response.status == Status.Created,
        body.fromJson[AntifraudListEntry] == Right(stored),
        actor == userId,
        sent.valueType == AntifraudValueType.prefix,
        sent.source == "manual",
        !sent.shadow,
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
    test("a malformed body, an unknown list type, and a missing or malformed userId are 400") {
      val lists = stub[AntifraudLists]
      for
        malformed <- call(lists, Request.post(base, Body.fromString("{")))
        unknown <- call(lists, Request.post(base, entryBody("""{"listType":"nope","valueType":"ip","value":"1.1.1.1"}""")))
        noUserInBody <- call(lists, Request.post(base, Body.fromString(prefixEntry)))
        badUserInBody <- call(lists, Request.post(base, Body.fromString(prefixEntry.dropRight(1) + ""","userId":"nope"}""")))
        noUser <- call(lists, Request.get(base))
        badUser <- call(lists, Request.get(base.addQueryParam("userId", "nope")))
        noUserOnDelete <- call(lists, Request.delete(base.addQueryParam("id", entryId.toString)))
      yield assertTrue(
        List(malformed, unknown, noUserInBody, badUserInBody, noUser, badUser, noUserOnDelete).map(_.status) == List.fill(7)(Status.BadRequest),
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
      val url = withUser("listType" -> "deny", "valueType" -> "prefix", "tenantId" -> "tenant-a", "limit" -> "10", "offset" -> "20")
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
      def get(params: (String, String)*) = call(lists, Request.get(withUser(params*)))
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
      def delete(id: String) = call(lists, Request.delete(withUser("id" -> id)))
      for
        _ <- lists.delete.succeedsWith(true)
        gone <- delete(entryId.toString)
        _ <- lists.delete.succeedsWith(false)
        missing <- delete(entryId.toString)
        malformed <- delete("nope")
      yield assertTrue(
        gone.status == Status.NoContent,
        missing.status == Status.NotFound,
        malformed.status == Status.BadRequest,
        lists.delete.calls.size == 2,
        lists.delete.calls.head == (entryId, userId),
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
      )
      for
        _ <- lists.audit.succeedsWith(List(record))
        ok <-
          call(lists, Request.get((base / "audit").addQueryParams(QueryParams("userId" -> userId, "entryId" -> entryId.toString, "limit" -> "5"))))
        body <- ok.body.asString
        badId <- call(lists, Request.get((base / "audit").addQueryParams(QueryParams("userId" -> userId, "entryId" -> "nope"))))
      yield assertTrue(
        ok.status == Status.Ok,
        body.fromJson[List[AntifraudListAudit]] == Right(List(record)),
        lists.audit.calls.head == (Some(entryId), 5),
        badId.status == Status.BadRequest,
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging

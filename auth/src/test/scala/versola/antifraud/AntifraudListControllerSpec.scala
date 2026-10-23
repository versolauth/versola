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

  private def createBody(entry: String, actor: String = """{"name":"alice","tenantId":"tenant-a"}""") =
    Body.fromString(s"""{"entry":$entry,"actor":$actor}""")

  private val prefixEntry = """{"listType":"deny","valueType":"prefix","value":"4915","tenantId":"tenant-a","reason":"sms pumping"}"""

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
        forged <- call(lists, Request.get(base.addQueryParam("actor", "alice")), wrongKey)
      yield assertTrue(anonymous.status == Status.Unauthorized, forged.status == Status.Unauthorized, lists.list.calls.isEmpty)
    },
    test("POST creates the entry as the named actor and answers 201 with it") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.create.succeedsWith(stored)
        response <- call(lists, Request.post(base, createBody(prefixEntry)))
        body <- response.body.asString
        (sent, actor) = lists.create.calls.head
      yield assertTrue(
        response.status == Status.Created,
        body.fromJson[AntifraudListEntry] == Right(stored),
        actor == ListActor("alice", Some(tenant)),
        sent.valueType == AntifraudValueType.prefix,
        sent.source == "manual",
        !sent.shadow,
      )
    },
    test("an operator is an actor without a tenant") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.create.succeedsWith(stored)
        _ <- call(lists, Request.post(base, createBody(prefixEntry, actor = """{"name":"root"}""")))
      yield assertTrue(lists.create.calls.head._2 == ListActor("root", None))
    },
    test("a phone is hashed the way an event's destination is, and stored as the hash") {
      val lists = stub[AntifraudLists]
      val entry = """{"listType":"deny","valueType":"phone_hash","phone":"+77011234567"}"""
      for
        _ <- lists.create.succeedsWith(stored)
        response <- call(lists, Request.post(base, createBody(entry)))
      yield assertTrue(response.status == Status.Created, lists.create.calls.head._1.value == "hash:+77011234567")
    },
    test("a phone with a value, a phone for another kind of value, an invalid phone or neither are 400") {
      val lists = stub[AntifraudLists]
      def status(entry: String) = call(lists, Request.post(base, createBody(entry))).map(_.status)
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
    test("a value the service refuses is 400 with its reason, and an actor it refuses is 403") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.create.failsWith(InvalidListEntry("not an IP address"))
        invalid <- call(lists, Request.post(base, createBody(prefixEntry)))
        reason <- invalid.body.asString
        _ <- lists.create.failsWith(ListForbidden("only an operator may change a global entry"))
        forbidden <- call(lists, Request.post(base, createBody(prefixEntry)))
      yield assertTrue(invalid.status == Status.BadRequest, reason == "not an IP address", forbidden.status == Status.Forbidden)
    },
    test("a malformed body, an unknown list type, and a missing actor are 400") {
      val lists = stub[AntifraudLists]
      for
        malformed <- call(lists, Request.post(base, Body.fromString("{")))
        unknown <- call(lists, Request.post(base, createBody("""{"listType":"nope","valueType":"ip","value":"1.1.1.1"}""")))
        noActor <- call(lists, Request.get(base))
      yield assertTrue(
        malformed.status == Status.BadRequest,
        unknown.status == Status.BadRequest,
        noActor.status == Status.BadRequest,
        lists.create.calls.isEmpty,
        lists.list.calls.isEmpty,
      )
    },
    test("POST preview answers the counts, and a tenant's admin may not preview a global entry") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.preview.succeedsWith(ListPreview(matched = Some(3), total = 1000))
        ok <- call(lists, Request.post(base / "preview", createBody(prefixEntry)))
        body <- ok.body.asString
        _ <- lists.preview.failsWith(ListForbidden("only an operator may change a global entry"))
        forbidden <- call(lists, Request.post(base / "preview", createBody(prefixEntry)))
      yield assertTrue(
        ok.status == Status.Ok,
        body.fromJson[ListPreview] == Right(ListPreview(matched = Some(3), total = 1000)),
        forbidden.status == Status.Forbidden,
      )
    },
    test("GET lists with the filter, the actor and the page") {
      val lists = stub[AntifraudLists]
      val url = base.addQueryParams(
        QueryParams(
          "actor" -> "alice",
          "actorTenantId" -> "tenant-a",
          "listType" -> "deny",
          "valueType" -> "prefix",
          "tenantId" -> "tenant-a",
          "limit" -> "10",
          "offset" -> "20",
        ),
      )
      for
        _ <- lists.list.succeedsWith(List(stored))
        response <- call(lists, Request.get(url))
        body <- response.body.asString
        (filter, actor, limit, offset) = lists.list.calls.head
      yield assertTrue(
        response.status == Status.Ok,
        body.fromJson[List[AntifraudListEntry]] == Right(List(stored)),
        filter == AntifraudListFilter(
          listType = Some(AntifraudListType.deny),
          valueType = Some(AntifraudValueType.prefix),
          tenantId = Some(Some(tenant)),
        ),
        actor == ListActor("alice", Some(tenant)),
        limit == 10,
        offset == 20,
      )
    },
    test(
      "GET lists: global=true asks for the global entries; with a tenant, an unknown value, a bad number or flag, an empty actor or a page out of range it is 400, and the service is not called",
    ) {
      val lists = stub[AntifraudLists]
      def get(params: (String, String)*) = call(lists, Request.get(base.addQueryParams(QueryParams(("actor" -> "root"), params*))))
      for
        _ <- lists.list.succeedsWith(Nil)
        global <- get("global" -> "true")
        both <- get("global" -> "true", "tenantId" -> "tenant-a")
        badType <- get("listType" -> "nope")
        notANumber <- get("limit" -> "many")
        notAFlag <- get("global" -> "maybe")
        noActor <- call(lists, Request.get(base.addQueryParams(QueryParams("actor" -> ""))))
        zero <- get("limit" -> "0")
        huge <- get("limit" -> "501")
        negative <- get("offset" -> "-1")
      yield assertTrue(
        global.status == Status.Ok,
        lists.list.calls.head._1.tenantId == Some(None),
        lists.list.calls.head._4 == 0,
        lists.list.calls.head._3 == 100,
        both.status == Status.BadRequest,
        badType.status == Status.BadRequest,
        notANumber.status == Status.BadRequest,
        notAFlag.status == Status.BadRequest,
        noActor.status == Status.BadRequest,
        List(zero, huge, negative).forall(_.status == Status.BadRequest),
        lists.list.calls.size == 1,
      )
    },
    test("DELETE answers 204, 404 for an entry that is not there, 403 for one the actor may not change, 400 for a bad id") {
      val lists = stub[AntifraudLists]
      def delete(id: String) =
        call(lists, Request.delete(base.addQueryParams(QueryParams("id" -> id, "actor" -> "alice", "actorTenantId" -> "tenant-a"))))
      for
        _ <- lists.delete.succeedsWith(true)
        gone <- delete(entryId.toString)
        _ <- lists.delete.succeedsWith(false)
        missing <- delete(entryId.toString)
        _ <- lists.delete.failsWith(ListForbidden("not an entry of the actor's tenant"))
        forbidden <- delete(entryId.toString)
        malformed <- delete("nope")
      yield assertTrue(
        gone.status == Status.NoContent,
        missing.status == Status.NotFound,
        forbidden.status == Status.Forbidden,
        malformed.status == Status.BadRequest,
        lists.delete.calls.size == 3,
        lists.delete.calls.head == (entryId, ListActor("alice", Some(tenant))),
      )
    },
    test("GET audit answers the records for operators and 403 for anyone else") {
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
        ok <- call(lists, Request.get((base / "audit").addQueryParams(QueryParams("actor" -> "root", "entryId" -> entryId.toString, "limit" -> "5"))))
        body <- ok.body.asString
        _ <- lists.audit.failsWith(ListForbidden("only an operator reads the audit"))
        forbidden <- call(lists, Request.get((base / "audit").addQueryParams(QueryParams("actor" -> "alice", "actorTenantId" -> "tenant-a"))))
        badId <- call(lists, Request.get((base / "audit").addQueryParams(QueryParams("actor" -> "root", "entryId" -> "nope"))))
      yield assertTrue(
        ok.status == Status.Ok,
        body.fromJson[List[AntifraudListAudit]] == Right(List(record)),
        lists.audit.calls.head == (Some(entryId), ListActor("root", None), 5),
        forbidden.status == Status.Forbidden,
        badId.status == Status.BadRequest,
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging

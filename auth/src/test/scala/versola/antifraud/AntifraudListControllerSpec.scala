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
  private val base = URL.empty / "antifraud" / "lists"

  private def token(key: SecretKey) =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def authorized(request: Request, key: SecretKey = config.central.secretKey): Task[Request] =
    token(key).map(t => request.addHeader(Header.Authorization.Bearer(t)).addHeader(Header.ContentType(MediaType.application.json)))

  private def routes(lists: AntifraudLists) =
    for
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudListController.routes.provideEnvironment(
            ZEnvironment(lists) ++ ZEnvironment(config) ++ ZEnvironment(RecordingAntifraudEvents()) ++ tracing,
          ),
        ),
      )
    yield ()

  /** Runs `request` against the controller with the given collaborators. */
  private def call(
      lists: AntifraudLists,
      request: Request,
      key: SecretKey = config.central.secretKey,
  ): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      _ <- routes(lists)
      signed <- authorized(request, key)
      response <- client.batched(signed)
    yield response

  /** What central sends to ask about an entry; `fields` replaces what it names of the defaults. */
  private def checkBody(fields: (String, String)*) =
    val defaults = Map(
      "listType" -> "\"deny\"",
      "valueType" -> "\"prefix\"",
      "value" -> "\"4915\"",
      "tenantId" -> "\"tenant-a\"",
      "reason" -> "\"sms pumping\"",
      "source" -> "\"manual\"",
      "shadow" -> "false",
      "preview" -> "false",
    )
    val merged = (defaults ++ fields).filter(_._2 != "null")
    Body.fromString(merged.map((k, v) => s""""$k":$v""").mkString("{", ",", "}"))

  private def check(body: Body) = Request.post(base / "check", body)

  def spec = suite("AntifraudListController")(
    test("a request without a token, or with one signed by another key, is 401 and reaches nothing") {
      val lists = stub[AntifraudLists]
      for
        client <- ZIO.service[Client]
        _ <- routes(lists)
        anonymousCheck <- client.batched(check(checkBody()))
        anonymousHits <- client.batched(Request.post(base / "hits", Body.fromString("""{"ids":[]}""")))
        forgedCheck <- call(lists, check(checkBody()), wrongKey)
        forgedHits <- call(lists, Request.post(base / "hits", Body.fromString("""{"ids":[]}""")), wrongKey)
      yield assertTrue(
        List(anonymousCheck, anonymousHits, forgedCheck, forgedHits).map(_.status) == List.fill(4)(Status.Unauthorized),
        lists.check.calls.isEmpty,
        lists.preview.calls.isEmpty,
        lists.hits.calls.isEmpty,
      )
    },
    test("check answers the canonical value of the entry it was asked about, and previews nothing") {
      val lists = stub[AntifraudLists]
      val expires = Instant.parse("2026-12-01T00:00:00Z")
      for
        _ <- lists.check.succeedsWith("4915")
        response <- call(lists, check(checkBody("expiresAt" -> s""""$expires"""", "shadow" -> "true", "source" -> "\"detector:prefix\"")))
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        text.fromJson[Json.Obj].exists(_.get("value").contains(Json.Str("4915"))),
        !text.contains("preview"),
        lists.check.calls == List(
          NewListEntry(
            listType = AntifraudListType.deny,
            valueType = AntifraudValueType.prefix,
            value = "4915",
            tenantId = Some(TenantId("tenant-a")),
            expiresAt = Some(expires),
            reason = Some("sms pumping"),
            source = "detector:prefix",
            shadow = true,
          ),
        ),
        lists.preview.calls.isEmpty,
      )
    },
    test("check of a global entry names no tenant") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.check.succeedsWith("4915")
        response <- call(lists, check(checkBody("tenantId" -> "null")))
      yield assertTrue(response.status == Status.Ok, lists.check.calls.map(_.tenantId) == List(None))
    },
    test("check hashes a phone the way an event's destination is hashed, and asks about the hash") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.check.succeedsWith("hash:+77011234567")
        response <- call(lists, check(checkBody("valueType" -> "\"phone_hash\"", "value" -> "null", "phone" -> "\"+77011234567\"")))
      yield assertTrue(response.status == Status.Ok, lists.check.calls.map(_.value) == List("hash:+77011234567"))
    },
    test("an entry with a phone and a value, a phone for another kind of value, an invalid phone, or neither is 400 and is not asked about") {
      val lists = stub[AntifraudLists]
      def status(fields: (String, String)*) = call(lists, check(checkBody(fields*))).map(_.status)
      for
        both <- status("valueType" -> "\"phone_hash\"", "phone" -> "\"+77011234567\"")
        wrongKind <- status("value" -> "null", "phone" -> "\"+77011234567\"")
        invalid <- status("valueType" -> "\"phone_hash\"", "value" -> "null", "phone" -> "\"12\"")
        neither <- status("value" -> "null")
      yield assertTrue(
        List(both, wrongKind, invalid, neither) == List.fill(4)(Status.BadRequest),
        lists.check.calls.isEmpty,
      )
    },
    test("check of an entry the service refuses is 400 with its reason") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.check.failsWith(InvalidListEntry("not an IP address"))
        invalid <- call(lists, check(checkBody("valueType" -> "\"ip\"", "value" -> "\"nope\"")))
        reason <- invalid.body.asString
      yield assertTrue(invalid.status == Status.BadRequest, reason == "not an IP address")
    },
    test("check with preview adds what the entry would have matched, asking the service once") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.preview.succeedsWith(("4915", ListPreview(matched = Some(3), total = 1000)))
        response <- call(lists, check(checkBody("preview" -> "true")))
        text <- response.body.asString
        json <- ZIO.fromEither(text.fromJson[Json.Obj]).mapError(RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        json.get("value").contains(Json.Str("4915")),
        json.get("preview").flatMap(_.as[ListPreview].toOption).contains(ListPreview(matched = Some(3), total = 1000)),
        lists.preview.calls.size == 1,
        lists.check.calls.isEmpty,
      )
    },
    test("check with preview of an entry the service refuses is 400 with its reason") {
      val lists = stub[AntifraudLists]
      for
        _ <- lists.preview.failsWith(InvalidListEntry("expiresAt is in the past"))
        invalid <- call(lists, check(checkBody("preview" -> "true")))
        reason <- invalid.body.asString
      yield assertTrue(invalid.status == Status.BadRequest, reason == "expiresAt is in the past")
    },
    test("a malformed body, an unknown list type, and a missing source or shadow are 400 and are not asked about") {
      val lists = stub[AntifraudLists]
      for
        malformed <- call(lists, check(Body.fromString("{")))
        unknown <- call(lists, check(checkBody("listType" -> "\"nope\"")))
        noSource <- call(lists, check(Body.fromString("""{"listType":"deny","valueType":"ip","value":"1.1.1.1","shadow":false,"preview":false}""")))
      yield assertTrue(
        List(malformed, unknown, noSource).map(_.status) == List.fill(3)(Status.BadRequest),
        lists.check.calls.isEmpty,
        lists.preview.calls.isEmpty,
      )
    },
    test("hits answers the counters of this cell") {
      val lists = stub[AntifraudLists]
      val id = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
      val at = Instant.parse("2026-11-01T00:00:00Z")
      for
        _ <- lists.hits.succeedsWith(List(ListHit(id, 7, at)))
        response <- call(lists, Request.post(base / "hits", Body.fromString(s"""{"ids":["$id"]}""")))
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        text.fromJson[List[ListHit]] == Right(List(ListHit(id, 7, at))),
        lists.hits.calls == List(List(id)),
      )
    },
    test("hits for more ids than the service answers is 400 with its reason") {
      val lists = stub[AntifraudLists]
      val ids = List.fill(AntifraudLists.MaxLimit + 1)(UUID.randomUUID())
      for
        _ <- lists.hits.failsWith(InvalidListEntry(s"at most ${AntifraudLists.MaxLimit} ids"))
        response <- call(lists, Request.post(base / "hits", Body.fromString(s"""{"ids":${ids.toJson}}""")))
        reason <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, reason == s"at most ${AntifraudLists.MaxLimit} ids", lists.hits.calls == List(ids))
    },
    test("hits with a malformed id is 400 and is not asked about") {
      val lists = stub[AntifraudLists]
      for
        response <- call(lists, Request.post(base / "hits", Body.fromString("""{"ids":["nope"]}""")))
      yield assertTrue(response.status == Status.BadRequest, lists.hits.calls.isEmpty)
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging

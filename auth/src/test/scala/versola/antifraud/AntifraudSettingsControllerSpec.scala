package versola.antifraud

import org.scalamock.stubs.ZIOStubs
import versola.audit.AuthAuditKinds
import versola.auth.TestEnvConfig
import versola.util.audit.AuditEvents
import versola.util.http.{NoopTracing, Observability}
import versola.util.{JWT, Patch}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object AntifraudSettingsControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val config = TestEnvConfig.coreConfig
  private val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
  private val userId = "00000000-0000-0000-0000-0000000000f1"
  private val base = URL.empty / "antifraud" / "settings"
  private val settings = Tuning.off.copy(blockAt = 80, shadowRules = Set("velocity_ip_1m"))

  private def token(key: SecretKey) =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(key),
    )

  private def call(
      tuning: AntifraudTuningStore,
      request: Request,
      key: SecretKey = config.central.secretKey,
  ): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          AntifraudSettingsController.routes.provideEnvironment(ZEnvironment(tuning) ++ ZEnvironment(config) ++ ZEnvironment[AuditEvents](AuditEvents.noop) ++ tracing),
        ),
      )
      signed <- token(key).map(t => request.addHeader(Header.Authorization.Bearer(t)).addHeader(Header.ContentType(MediaType.application.json)))
      response <- client.batched(signed)
    yield response

  private def withUser(params: (String, String)*) = base.addQueryParams(QueryParams(("userId" -> userId), params*))

  private def patchBody(patch: TuningPatch) = Body.fromString(s"""{"patch":${patch.toJson},"userId":"$userId"}""")

  /** The settings of a body: flat, with the `version` next to them, which is not one of them. */
  private def tuningOf(text: String): Either[String, Tuning] =
    text.fromJson[Json.Obj].flatMap(obj => Json.Obj(obj.fields.filterNot(_._1 == "version")).as[Tuning])

  private def blockAt(value: Int) = TuningPatch.empty.copy(blockAt = Some(Patch.Modified(value)))

  private def body(settings: String) = Body.fromString(s"""{"settings":$settings,"userId":"$userId"}""")

  def spec = suite("AntifraudSettingsController")(
    test("a request without a token, or with one signed by another key, is 401 and reaches nothing") {
      val tuning = stub[AntifraudTuningStore]
      for
        client <- ZIO.service[Client]
        tracing <- NoopTracing.layer.build
        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            AntifraudSettingsController.routes.provideEnvironment(ZEnvironment(tuning) ++ ZEnvironment(config) ++ ZEnvironment[AuditEvents](AuditEvents.noop) ++ tracing),
          ),
        )
        anonymous <- client.batched(Request.get(base))
        forged <- call(tuning, Request.get(withUser()), wrongKey)
      yield assertTrue(anonymous.status == Status.Unauthorized, forged.status == Status.Unauthorized, tuning.stored.calls.isEmpty)
    },
    test("GET gives the stored settings with their version, in the body and as the ETag") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.stored.succeedsWith(StoredTuning(settings, 3))
        response <- call(tuning, Request.get(withUser()))
        text <- response.body.asString
        json <- ZIO.fromEither(text.fromJson[Json.Obj]).mapError(RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        tuningOf(text) == Right(settings),
        json.get("version").contains(Json.Num(3)),
        response.headers.get("ETag").contains("\"3\""),
      )
    },
    test("with no row GET gives everything off at version 0") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.stored.succeedsWith(StoredTuning(Tuning.off, 0))
        response <- call(tuning, Request.get(withUser()))
        text <- response.body.asString
      yield assertTrue(tuningOf(text) == Right(Tuning.off), response.headers.get("ETag").contains("\"0\""))
    },
    test("PUT replaces the settings as the user central names, on the version in If-Match, and answers the new version") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.succeedsWith(4L)
        response <- call(tuning, Request.put(base, body(settings.toJson)).addHeader(Header.Custom("If-Match", "\"3\"")))
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        tuningOf(text) == Right(settings),
        text.fromJson[Json.Obj].exists(_.get("version").contains(Json.Num(4))),
        response.headers.get("ETag").contains("\"4\""),
        tuning.put.calls.map((s, u, v, _) => (s, u, v)) == List((settings, userId, Some(3L))),
        tuning.put.calls.map(_._4.kind) == List(AuthAuditKinds.AntifraudSettingsChanged),
      )
    },
    test("PUT takes the version from the body when there is no If-Match; both must agree, and a header that is not a version is 400") {
      val tuning = stub[AntifraudTuningStore]
      def withVersion(version: Long) = Body.fromString(s"""{"settings":${settings.toJson},"userId":"$userId","version":$version}""")
      for
        _ <- tuning.put.succeedsWith(8L)
        fromBody <- call(tuning, Request.put(base, withVersion(7)))
        agree <- call(tuning, Request.put(base, withVersion(7)).addHeader(Header.Custom("If-Match", "7")))
        differ <- call(tuning, Request.put(base, withVersion(7)).addHeader(Header.Custom("If-Match", "\"6\"")))
        garbage <- call(tuning, Request.put(base, body(settings.toJson)).addHeader(Header.Custom("If-Match", "*")))
      yield assertTrue(
        fromBody.status == Status.Ok,
        agree.status == Status.Ok,
        differ.status == Status.BadRequest,
        garbage.status == Status.BadRequest,
        tuning.put.calls.map(_._3) == List(Some(7L), Some(7L)),
      )
    },
    test("PUT that names no version replaces the settings unconditionally and answers the new version") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.succeedsWith(9L)
        response <- call(tuning, Request.put(base, body(settings.toJson)))
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        text.fromJson[Json.Obj].exists(_.get("version").contains(Json.Num(9))),
        response.headers.get("ETag").contains("\"9\""),
        tuning.put.calls.map((s, u, v, _) => (s, u, v)) == List((settings, userId, None)),
      )
    },
    test("PUT on a version the settings have left is 412 with the version they are at as the ETag") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.failsWith(VersionMismatch(5))
        response <- call(tuning, Request.put(base, body(settings.toJson)).addHeader(Header.Custom("If-Match", "\"3\"")))
      yield assertTrue(response.status == Status.PreconditionFailed, response.headers.get("ETag").contains("\"5\""))
    },
    test("settings the service refuses are 400 with its reason") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.put.failsWith(InvalidTuning("blockAt must be between 0 and 100000"))
        response <- call(tuning, Request.put(base, body(settings.toJson)).addHeader(Header.Custom("If-Match", "\"1\"")))
        text <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, text == "blockAt must be between 0 and 100000")
    },
    test("PATCH hands the typed patch to the store as the user central names, on the version in If-Match, and answers all the settings") {
      val tuning = stub[AntifraudTuningStore]
      val patch = blockAt(70)
      for
        _ <- tuning.patch.succeedsWith(StoredTuning(settings.copy(blockAt = 70), 4))
        response <- call(tuning, Request.patch(base, patchBody(patch)).addHeader(Header.Custom("If-Match", "\"3\"")))
        text <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        tuningOf(text) == Right(settings.copy(blockAt = 70)),
        text.fromJson[Json.Obj].exists(_.get("version").contains(Json.Num(4))),
        response.headers.get("ETag").contains("\"4\""),
        tuning.patch.calls.map((p, u, v, _) => (p, u, v)) == List((patch, userId, Some(3L))),
        tuning.patch.calls.map(_._4.kind) == List(AuthAuditKinds.AntifraudSettingsChanged),
      )
    },
    test("PATCH needs no version; one in the body is taken, and one that differs from If-Match is 400") {
      val tuning = stub[AntifraudTuningStore]
      val patch = blockAt(70)
      def withVersion(version: Long) = Body.fromString(s"""{"patch":${patch.toJson},"userId":"$userId","version":$version}""")
      for
        _ <- tuning.patch.succeedsWith(StoredTuning(settings, 4))
        plain <- call(tuning, Request.patch(base, patchBody(patch)))
        fromBody <- call(tuning, Request.patch(base, withVersion(3)))
        differ <- call(tuning, Request.patch(base, withVersion(3)).addHeader(Header.Custom("If-Match", "\"2\"")))
      yield assertTrue(
        plain.status == Status.Ok,
        fromBody.status == Status.Ok,
        differ.status == Status.BadRequest,
        tuning.patch.calls.map(_._3) == List(None, Some(3L)),
      )
    },
    test("PATCH on a version the settings have left is 412 with the version they are at, and a result that is not valid is 400") {
      val tuning = stub[AntifraudTuningStore]
      val patch = blockAt(-1)
      for
        _ <- tuning.patch.failsWith(VersionMismatch(6))
        stale <- call(tuning, Request.patch(base, patchBody(patch)).addHeader(Header.Custom("If-Match", "\"3\"")))
        _ <- tuning.patch.failsWith(InvalidTuning("blockAt must be between 0 and 100000"))
        invalid <- call(tuning, Request.patch(base, patchBody(patch)))
        reason <- invalid.body.asString
        notAnObject <- call(tuning, Request.patch(base, Body.fromString(s"""{"patch":[1],"userId":"$userId"}""")))
        notANumber <- call(tuning, Request.patch(base, Body.fromString(s"""{"patch":{"blockAt":"high"},"userId":"$userId"}""")))
      yield assertTrue(
        stale.status == Status.PreconditionFailed,
        stale.headers.get("ETag").contains("\"6\""),
        invalid.status == Status.BadRequest,
        reason == "blockAt must be between 0 and 100000",
        notAnObject.status == Status.BadRequest,
        notANumber.status == Status.BadRequest,
        tuning.patch.calls.size == 2,
      )
    },
    test("PATCH with a member the settings do not have is 400 and never reaches the store; null is read as a removal") {
      val tuning = stub[AntifraudTuningStore]
      def raw(patch: String) = Body.fromString(s"""{"patch":$patch,"userId":"$userId"}""")
      for
        _ <- tuning.patch.succeedsWith(StoredTuning(settings, 4))
        typo <- call(tuning, Request.patch(base, raw("""{"blokAt":10}""")))
        reason <- typo.body.asString
        nestedTypo <- call(tuning, Request.patch(base, raw("""{"pow":{"minDificulty":3}}""")))
        entryTypo <- call(tuning, Request.patch(base, raw("""{"smsLimits":{"byPrefix":{"49":{"sentPerHur":30}}}}""")))
        nulls <- call(tuning, Request.patch(base, raw("""{"blockAt":null,"ruleWeights":{"watch_list:cidr":null}}""")))
      yield assertTrue(
        typo.status == Status.BadRequest,
        reason.contains("invalid extra field"),
        nestedTypo.status == Status.BadRequest,
        entryTypo.status == Status.BadRequest,
        nulls.status == Status.Ok,
        tuning.patch.calls.map((p, u, v, _) => (p, u, v)) == List(
          (
            TuningPatch.empty.copy(blockAt = Some(Patch.Deleted), ruleWeights = Some(Patch.Modified(Map("watch_list:cidr" -> Patch.Deleted)))),
            userId,
            None,
          ),
        ),
      )
    },
    test("PUT with a member the settings do not have is 400 and never reaches the store") {
      val tuning = stub[AntifraudTuningStore]
      for
        typo <- call(tuning, Request.put(base, body("""{"blokAt":10}""")))
        nested <- call(tuning, Request.put(base, body("""{"pow":{"minDificulty":3}}""")))
      yield assertTrue(typo.status == Status.BadRequest, nested.status == Status.BadRequest, tuning.put.calls.isEmpty)
    },
    test("DELETE puts the seeded values back, and does not have to name a version; one it names is checked") {
      val tuning = stub[AntifraudTuningStore]
      for
        _ <- tuning.reset.succeedsWith(6L)
        plain <- call(tuning, Request.delete(withUser()))
        named <- call(tuning, Request.delete(withUser()).addHeader(Header.Custom("If-Match", "\"5\"")))
        _ <- tuning.reset.failsWith(VersionMismatch(9))
        stale <- call(tuning, Request.delete(withUser()).addHeader(Header.Custom("If-Match", "\"5\"")))
      yield assertTrue(
        plain.status == Status.NoContent,
        named.status == Status.NoContent,
        stale.status == Status.PreconditionFailed,
        stale.headers.get("ETag").contains("\"9\""),
        tuning.reset.calls.map((u, v, _) => (u, v)) == List((userId, None), (userId, Some(5L)), (userId, Some(5L))),
        tuning.reset.calls.map(_._3.kind).distinct == List(AuthAuditKinds.AntifraudSettingsChanged),
      )
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging

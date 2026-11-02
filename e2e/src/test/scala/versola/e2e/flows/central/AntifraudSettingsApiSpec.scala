package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** The antifraud settings' admin API, through central to auth and Postgres: the version and `ETag` of a read, and what a
  * change that names no version, a stale one or one that is not a version is answered with.
  *
  * Central is called directly, as edge would, with the `userId` it adds (`asAdmin`). **Nothing here changes the settings**:
  * they are one row for the deployment, and `AntifraudOtpSpec` runs its instances on it, so every case here is a refusal
  * (the change that is accepted, and picked up by both instances, is in `AntifraudOtpSpec`). The permission that guards the
  * writes (`antifraud:operate`) is edge's to check and is covered by the unit specs of `BootstrapService`.
  */
object AntifraudSettingsApiSpec extends CentralApiSpec:

  private val settings = "/antifraud/settings"
  private val adminId = "00000000-0000-0000-0000-0000000e2e02"

  /** What a read says, as a body that can be sent back: the settings without their version. */
  private def settingsOf(read: ApiResult): Task[Json.Obj] =
    read.obj.map(json => Json.Obj(json.fields.filterNot(_._1 == "version")))

  private def body(settings: Json.Obj, extra: (String, Json)*) =
    Json.Obj(Chunk("settings" -> settings) ++ extra)

  def spec = suite("Central API: antifraud settings")(
    test("a read gives the settings with their version, and the version is the ETag") {
      for
        central <- api
        read <- central.get(settings)
        json <- read.obj
        etag = read.response.headers.get("ETag")
        again <- central.get(settings)
      yield assertTrue(
        read.status == Status.Ok,
        json.int("version").exists(_ >= 1),
        etag.contains(s"\"${json.int("version").getOrElse(-1)}\""),
        json.has("challengeAt"),
        json.has("thresholds"),
        // reading changes nothing
        again.response.headers.get("ETag") == etag,
      )
    },
    test("a change that names no version is 428, one on a version the row has left is 412 with the current version, and nothing changes") {
      for
        central <- api
        operator = central.asAdmin(adminId)
        read <- central.get(settings)
        version = read.response.headers.get("ETag")
        current <- settingsOf(read)
        noVersion <- operator.put(settings, body(current))
        stale <- operator.withHeader("If-Match", "\"0\"").put(settings, body(current))
        staleInBody <- operator.put(settings, body(current, "version" -> Json.Num(0)))
        after <- central.get(settings)
      yield assertTrue(
        noVersion.status == Status.PreconditionRequired,
        stale.status == Status.PreconditionFailed,
        stale.response.headers.get("ETag") == version,
        staleInBody.status == Status.PreconditionFailed,
        after.response.headers.get("ETag") == version,
      )
    },
    test("an If-Match that is not a version, or that differs from the version in the body, is 400") {
      for
        central <- api
        operator = central.asAdmin(adminId)
        read <- central.get(settings)
        version = read.response.headers.get("ETag").getOrElse("\"1\"")
        current <- settingsOf(read)
        wildcard <- operator.withHeader("If-Match", "*").put(settings, body(current))
        weak <- operator.withHeader("If-Match", s"W/$version").put(settings, body(current))
        differs <- operator.withHeader("If-Match", version).put(settings, body(current, "version" -> Json.Num(999)))
        resetStale <- operator.withHeader("If-Match", "\"0\"").delete(settings)
        after <- central.get(settings)
      yield assertTrue(
        wildcard.status == Status.BadRequest,
        weak.status == Status.BadRequest,
        differs.status == Status.BadRequest,
        // a reset may name a version, and one that is stale is refused too
        resetStale.status == Status.PreconditionFailed,
        after.response.headers.get("ETag").contains(version),
      )
    },
    test("a change without the admin's user id is a bad request, and a call without the central credentials is 401") {
      for
        central <- api
        read <- central.get(settings)
        current <- settingsOf(read)
        noUser <- central.withHeader("If-Match", "\"1\"").put(settings, body(current))
        anonymous <- central.anonymous.asAdmin(adminId).get(settings)
      yield assertTrue(noUser.status == Status.BadRequest, anonymous.status == Status.Unauthorized)
    },
    test("the rule catalogue and the status are read through central") {
      for
        central <- api
        rules <- central.get("/antifraud/rules")
        names <- rules.obj.map(_.strings("rules"))
        status <- central.get("/antifraud/status")
        json <- status.obj
      yield assertTrue(
        rules.status == Status.Ok,
        names.contains("velocity_phone_1h"),
        names.contains("deny_list:ip"),
        status.status == Status.Ok,
        json.obj("decision").isDefined,
        json.obj("lists").flatMap(_.bool("enabled")).isDefined,
        json.obj("tuning").flatMap(_.bool("available")).isDefined,
      )
    },
  )

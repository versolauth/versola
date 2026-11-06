package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** The antifraud settings' admin API, through central to auth and Postgres: the version and `ETag` of a read, and what a
  * change on a stale version, on one that is not a version, or with a body that is not a patch is answered with.
  *
  * Central is called directly, as edge would, with the `userId` it adds (`asAdmin`). **Nothing here changes the settings**:
  * they are one row for the deployment, and `AntifraudOtpSpec` runs its instances on it, so every case here is a refusal
  * (the changes that are accepted, and picked up by both instances, are in `AntifraudOtpSpec`: a change that names no version
  * is accepted, so it is not a refusal to test here). The permission that guards the
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
    test("a change on a version the row has left is 412 with the current version, for PUT and PATCH, and nothing changes") {
      for
        central <- api
        operator = central.asAdmin(adminId)
        read <- central.get(settings)
        version = read.response.headers.get("ETag")
        current <- settingsOf(read)
        stale <- operator.withHeader("If-Match", "\"0\"").put(settings, body(current))
        stalePatch <- operator.withHeader("If-Match", "\"0\"").patch(settings, Json.Obj("blockAt" -> Json.Num(1)))
        stalePatchInBody <- operator.patch(settings, Json.Obj("blockAt" -> Json.Num(1), "version" -> Json.Num(0)))
        staleInBody <- operator.put(settings, body(current, "version" -> Json.Num(0)))
        after <- central.get(settings)
      yield assertTrue(
        stale.status == Status.PreconditionFailed,
        stale.response.headers.get("ETag") == version,
        stalePatch.status == Status.PreconditionFailed,
        stalePatch.response.headers.get("ETag") == version,
        stalePatchInBody.status == Status.PreconditionFailed,
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
        patchWildcard <- operator.withHeader("If-Match", "*").patch(settings, Json.Obj("blockAt" -> Json.Num(1)))
        patchInvalid <- operator.patch(settings, Json.Obj("blockAt" -> Json.Num(-1)))
        weak <- operator.withHeader("If-Match", s"W/$version").put(settings, body(current))
        differs <- operator.withHeader("If-Match", version).put(settings, body(current, "version" -> Json.Num(999)))
        resetStale <- operator.withHeader("If-Match", "\"0\"").delete(settings)
        after <- central.get(settings)
      yield assertTrue(
        wildcard.status == Status.BadRequest,
        patchWildcard.status == Status.BadRequest,
        // a patch whose result is not valid is refused and writes nothing
        patchInvalid.status == Status.BadRequest,
        weak.status == Status.BadRequest,
        differs.status == Status.BadRequest,
        // a reset may name a version, and one that is stale is refused too
        resetStale.status == Status.PreconditionFailed,
        after.response.headers.get("ETag").contains(version),
      )
    },
    test("a change without the admin's user id is a bad request (a patch too), and a call without the central credentials is 401") {
      for
        central <- api
        read <- central.get(settings)
        current <- settingsOf(read)
        noUser <- central.withHeader("If-Match", "\"1\"").put(settings, body(current))
        noUserPatch <- central.patch(settings, Json.Obj("blockAt" -> Json.Num(1)))
        anonymous <- central.anonymous.asAdmin(adminId).get(settings)
      yield assertTrue(noUser.status == Status.BadRequest, noUserPatch.status == Status.BadRequest, anonymous.status == Status.Unauthorized)
    },
    test("a PATCH or a PUT with a member the settings do not have is 400, and nothing changes") {
      for
        central <- api
        operator = central.asAdmin(adminId)
        read <- central.get(settings)
        version = read.response.headers.get("ETag")
        current <- settingsOf(read)
        typo <- operator.patch(settings, Json.Obj("blokAt" -> Json.Num(10)))
        nested <- operator.patch(settings, Json.Obj("pow" -> Json.Obj("minDificulty" -> Json.Num(3))))
        entry <- operator.patch(
          settings,
          Json.Obj("smsLimits" -> Json.Obj("byPrefix" -> Json.Obj("49" -> Json.Obj("sentPerHur" -> Json.Num(30))))),
        )
        putTypo <- operator.put(settings, body(Json.Obj(current.fields :+ ("blokAt" -> Json.Num(10)))))
        after <- central.get(settings)
      yield assertTrue(
        typo.status == Status.BadRequest,
        nested.status == Status.BadRequest,
        entry.status == Status.BadRequest,
        putTypo.status == Status.BadRequest,
        after.response.headers.get("ETag") == version,
      )
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

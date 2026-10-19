package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** `PATCH /configuration/challenges/challenge-settings`: a JSON merge patch of the tenant's record at the level of its
  * members, so that the admin console's Security page can save one group without sending the others back -- and without
  * overwriting what another admin changed in the meantime.
  *
  * Each test creates its own tenant (a new tenant is created with its settings, which is what a patch needs). Central
  * serves reads of the settings from a cache a Postgres notification refreshes, so every read that follows a write polls
  * until it shows that write ([[CentralApiSpec.eventually]]); an assertion on "the others are untouched" is made on the
  * read that already shows the change, otherwise it would compare against the state from before.
  */
object ChallengeSettingsPatchApiSpec extends CentralApiSpec:

  private val tenants = "/configuration/tenants"
  private val settingsPath = "/configuration/challenges/challenge-settings"

  private def withTenant[A](test: (CentralApi, String) => Task[A]): RIO[CentralApi, A] =
    for
      central <- api
      tenantId <- CentralApi.id("e2e-cs-patch")
      created <- central.post(tenants, Fixtures.tenant(tenantId))
      _ <- ZIO.fail(RuntimeException(s"Could not create tenant '$tenantId': ${created.status} ${created.body}"))
        .unless(created.status.isSuccess)
      _ <- readSettings(central, tenantId)(_ => true)
      result <- test(central, tenantId).ensuring(central.delete(tenants, "tenantId" -> tenantId).ignore)
    yield result

  /** The `settings` object of the tenant, once `condition` holds of it (or the last one read, on timeout). */
  private def readSettings(central: CentralApi, tenantId: String)(condition: Json.Obj => Boolean): Task[Json.Obj] =
    eventually(
      central.get(settingsPath, "tenantId" -> tenantId)
        .flatMap(_.obj)
        .map(_.obj("settings").getOrElse(Json.Obj())),
    )(settings => settings.fields.nonEmpty && condition(settings))

  private def patch(central: CentralApi, tenantId: String, members: (String, Json)*): Task[ApiResult] =
    central.patch(settingsPath, Json.Obj(Chunk.from(("tenantId" -> Json.Str(tenantId)) +: members)))

  /** Every member except the named ones, as a map, for "nothing else moved". */
  private def without(settings: Json.Obj, names: String*): Map[String, Json] =
    settings.fields.filterNot(field => names.contains(field._1)).toMap

  private def hasNoValue(settings: Json.Obj, name: String): Boolean =
    settings.fields.forall(field => field._1 != name || field._2 == Json.Null)

  def spec = suite("PATCH challenge-settings")(
    test("changes the members it names and leaves every other member as it was") {
      withTenant: (central, tenantId) =>
        for
          before <- readSettings(central, tenantId)(_ => true)
          result <- patch(central, tenantId, "sessionTtlSeconds" -> Json.Num(7200), "sessionIdleTtlSeconds" -> Json.Num(600))
          after <- readSettings(central, tenantId)(_.int("sessionTtlSeconds").contains(7200))
        yield assertTrue(
          result.status == Status.NoContent,
          after.int("sessionIdleTtlSeconds").contains(600),
          without(after, "sessionTtlSeconds", "sessionIdleTtlSeconds") == without(before, "sessionTtlSeconds", "sessionIdleTtlSeconds"),
        )
    },
    test("two sequential patches of different groups both persist") {
      withTenant: (central, tenantId) =>
        for
          before <- readSettings(central, tenantId)(_ => true)
          first <- patch(central, tenantId, "otpLength" -> Json.Num(8), "otpResendAfter" -> Json.Num(45))
          second <- patch(
            central,
            tenantId,
            "logoLight" -> Json.Str("https://cdn.example.test/light.svg"),
            "logoDark" -> Json.Str("https://cdn.example.test/dark.svg"),
          )
          after <- readSettings(central, tenantId)(_.str("logoDark").isDefined)
        yield assertTrue(
          first.status == Status.NoContent,
          second.status == Status.NoContent,
          after.int("otpLength").contains(8),
          after.int("otpResendAfter").contains(45),
          after.str("logoLight").contains("https://cdn.example.test/light.svg"),
          without(after, "otpLength", "otpResendAfter", "logoLight", "logoDark") ==
            without(before, "otpLength", "otpResendAfter", "logoLight", "logoDark"),
        )
    },
    test("patches of different groups sent at the same time all land") {
      withTenant: (central, tenantId) =>
        for
          results <- ZIO.foreachPar(
            List(
              List("otpLength" -> Json.Num(8)),
              List("sessionTtlSeconds" -> Json.Num(7200)),
              List("requireDpopNonce" -> Json.Bool(true)),
              List("ipHeader" -> Json.Str("X-Real-IP")),
              List("userAgentTtlSeconds" -> Json.Num(86400)),
              List("otpResendAfter" -> Json.Num(45)),
            ),
          )(members => patch(central, tenantId, members*))
          after <- readSettings(central, tenantId)(settings =>
            settings.int("otpLength").contains(8) && settings.int("sessionTtlSeconds").contains(7200) &&
              settings.bool("requireDpopNonce").contains(true) && settings.str("ipHeader").contains("X-Real-IP") &&
              settings.int("userAgentTtlSeconds").contains(86400) && settings.int("otpResendAfter").contains(45),
          )
        yield assertTrue(
          results.forall(_.status == Status.NoContent),
          after.int("otpLength").contains(8),
          after.int("sessionTtlSeconds").contains(7200),
          after.bool("requireDpopNonce").contains(true),
          after.str("ipHeader").contains("X-Real-IP"),
          after.int("userAgentTtlSeconds").contains(86400),
          after.int("otpResendAfter").contains(45),
        )
    },
    test("null clears an optional member, and absent keeps it") {
      withTenant: (central, tenantId) =>
        for
          set <- patch(central, tenantId, "sessionIdleTtlSeconds" -> Json.Num(600))
          _ <- readSettings(central, tenantId)(_.int("sessionIdleTtlSeconds").contains(600))
          untouched <- patch(central, tenantId, "otpLength" -> Json.Num(8))
          kept <- readSettings(central, tenantId)(_.int("otpLength").contains(8))
          cleared <- patch(central, tenantId, "sessionIdleTtlSeconds" -> Json.Null)
          after <- readSettings(central, tenantId)(hasNoValue(_, "sessionIdleTtlSeconds"))
        yield assertTrue(
          set.status == Status.NoContent,
          untouched.status == Status.NoContent,
          kept.int("sessionIdleTtlSeconds").contains(600),
          cleared.status == Status.NoContent,
          hasNoValue(after, "sessionIdleTtlSeconds"),
          after.int("otpLength").contains(8),
        )
    },
    test("refuses null for a member that is not optional, and stores nothing") {
      withTenant: (central, tenantId) =>
        for
          before <- readSettings(central, tenantId)(_ => true)
          result <- patch(central, tenantId, "otpLength" -> Json.Null)
          after <- readSettings(central, tenantId)(_ => true)
        yield assertTrue(result.status == Status.BadRequest, result.body.contains("otpLength"), after == before)
    },
    test("refuses a key the record does not have, and stores nothing") {
      withTenant: (central, tenantId) =>
        for
          before <- readSettings(central, tenantId)(_ => true)
          result <- patch(central, tenantId, "otpLenght" -> Json.Num(8), "sessionTtlSeconds" -> Json.Num(7200))
          after <- readSettings(central, tenantId)(_ => true)
        yield assertTrue(result.status == Status.BadRequest, after == before)
    },
    test("refuses a patch whose resulting record is invalid, and stores nothing") {
      withTenant: (central, tenantId) =>
        for
          before <- readSettings(central, tenantId)(_ => true)
          range <- patch(central, tenantId, "clientAssertionMaxLifetimeSeconds" -> Json.Num(5))
          // Only one file of the logo pair is set: valid for neither the stored nor the patched record on its own.
          pair <- patch(central, tenantId, "logoLight" -> Json.Str("https://cdn.example.test/light.svg"))
          profile <- patch(central, tenantId, "securityProfile" -> Json.Str("standard"))
          after <- readSettings(central, tenantId)(_ => true)
        yield assertTrue(
          range.status == Status.BadRequest,
          range.body.contains("clientAssertionMaxLifetimeSeconds"),
          pair.status == Status.BadRequest,
          pair.body.contains("set or cleared together"),
          profile.status == Status.BadRequest,
          after == before,
        )
    },
    test("naming the tenant's own security profile is accepted and changes nothing") {
      withTenant: (central, tenantId) =>
        for
          before <- readSettings(central, tenantId)(_ => true)
          result <- patch(central, tenantId, "securityProfile" -> Json.Str("fapi2"))
          after <- readSettings(central, tenantId)(_ => true)
        yield assertTrue(result.status == Status.NoContent, after == before)
    },
    test("answers 404 for a tenant that has no settings, and creates none") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-cs-patch-missing")
        result <- patch(central, tenantId, "otpLength" -> Json.Num(8))
        read <- central.get(settingsPath, "tenantId" -> tenantId).flatMap(_.obj)
      yield assertTrue(result.status == Status.NotFound, read.obj("settings").isEmpty)
    },
    test("answers 401 without credentials, and stores nothing") {
      withTenant: (central, tenantId) =>
        for
          before <- readSettings(central, tenantId)(_ => true)
          result <- patch(central.anonymous, tenantId, "otpLength" -> Json.Num(8))
          after <- readSettings(central, tenantId)(_ => true)
        yield assertTrue(result.status == Status.Unauthorized, after == before)
    },
  )

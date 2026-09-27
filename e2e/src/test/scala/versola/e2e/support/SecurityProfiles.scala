package versola.e2e.support

import zio.*
import zio.http.Status
import zio.json.ast.Json

/** The tenant security profile (#353) as the e2e suite has to live with it.
  *
  * Every tenant is on FAPI 2.0 by default -- the seed `default` tenant included, since the
  * migration that introduced the setting moved it there -- and FAPI 2.0 admits no
  * `client_secret` client, no public one, no bearer token and no plain `/authorize`. Almost
  * every spec in this suite registers exactly such clients in `default`, because what it
  * tests is something else entirely. [[ensureStandard]] therefore puts `default` on the
  * `standard` profile before any spec runs, so each keeps testing what it names; the specs
  * that are about the profile create a tenant of their own, which starts on FAPI 2.0.
  *
  * Switching *to* `standard` is never refused, and the seeded `central-admin` client is
  * conformant under either profile, so this changes nothing a spec could otherwise observe.
  */
object SecurityProfiles:

  /** Reads the tenant's challenge settings and writes them back unchanged but for the
    * profile: the upsert takes the whole document, and a spec's own settings (ACR vocabulary,
    * mTLS header) must survive. A no-op for a tenant already on `standard`. */
  def ensureStandard(api: CentralApi, tenantId: String = Fixtures.defaultTenant): Task[Unit] =
    for
      result <- set(api, tenantId, "standard")
      _ <- ZIO.fail(RuntimeException(s"Could not put tenant '$tenantId' on the standard profile: ${result.status} ${result.body}"))
        .unless(result.status == Status.NoContent)
      // Registration reads the profile fresh, but a spec may read the settings back through
      // central's cache: wait until that shows the switch too.
      _ <- profileOf(api, tenantId)
        .repeat(Schedule.spaced(100.millis) *> Schedule.recurUntil[Option[String]](_.contains("standard")))
        .timeout(10.seconds)
        .withClock(Clock.ClockLive)
    yield ()

  def profileOf(api: CentralApi, tenantId: String): Task[Option[String]] =
    api.get("/configuration/challenges/challenge-settings", "tenantId" -> tenantId)
      .flatMap(_.obj)
      .map(_.obj("settings").flatMap(_.str("securityProfile")))

  /** Writes `profile` over the tenant's stored settings, answering whatever central answers --
    * a `409` listing the violating clients for a refused switch to `fapi2`. */
  def set(api: CentralApi, tenantId: String, profile: String): Task[ApiResult] =
    for
      // Central answers reads from a cache a Postgres notification refreshes, so a tenant
      // created a moment ago may not have its settings there yet.
      read = api.get("/configuration/challenges/challenge-settings", "tenantId" -> tenantId).flatMap(_.obj)
      current <- read
        .repeat(Schedule.spaced(100.millis) *> Schedule.recurUntil[Json.Obj](_.obj("settings").isDefined))
        .timeout(10.seconds)
        .withClock(Clock.ClockLive)
        .someOrElseZIO(read)
      settings <- ZIO.fromOption(current.obj("settings"))
        .orElseFail(RuntimeException(s"Tenant '$tenantId' has no challenge settings: ${current}"))
      result <-
        if settings.str("securityProfile").contains(profile) then
          ZIO.succeed(ApiResult(zio.http.Response.status(Status.NoContent), ""))
        else
          api.put(
            "/configuration/challenges/challenge-settings",
            Json.Obj(settings.fields.filterNot(_._1 == "securityProfile") :+ ("securityProfile" -> Json.Str(profile))),
          )
    yield result

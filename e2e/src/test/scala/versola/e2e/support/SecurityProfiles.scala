package versola.e2e.support

import zio.*
import zio.http.Status
import zio.json.ast.Json

/** The tenant security profile (#353) as the e2e suite has to live with it.
  *
  * A tenant's profile is chosen when the tenant is created and never changed, and every tenant
  * -- `default` included -- is created on FAPI 2.0 unless the request says otherwise. FAPI 2.0
  * admits no `client_secret` client, no public one, no bearer token and no plain `/authorize`.
  * Almost every spec in this suite registers exactly such clients, because what it tests is
  * something else entirely. They therefore register them in [[Fixtures.suiteTenant]], a tenant
  * [[ensureSuiteTenant]] creates on `standard` before any spec runs, and leave `default` as
  * the deployment made it. The specs that are about the profile create a tenant of their own,
  * with [[withTenant]], on whichever profile they are about.
  */
object SecurityProfiles:

  private val suiteTenantReady = java.util.concurrent.atomic.AtomicBoolean(false)

  /** Creates the suite's tenant on `standard`, with what its specs register clients against.
    *
    * Every spec's layer calls this, and so does every run against a database the last one left
    * behind, so it is idempotent: posting the tenant again is accepted for the same profile
    * (and refused for any other), and the role is only created when it is missing. After the
    * first call in a JVM it does nothing. */
  def ensureSuiteTenant(api: CentralApi): Task[Unit] =
    ZIO.unless(suiteTenantReady.get)(createSuiteTenant(api) *> ZIO.succeed(suiteTenantReady.set(true))).unit

  private def createSuiteTenant(api: CentralApi): Task[Unit] =
    for
      // Served by the same edge as `default`: the edge specs sync the clients and presets they
      // register here, and an edge only receives those of the tenants it is assigned.
      tenants <- api.get("/configuration/tenants").flatMap(_.items("tenants"))
      edgeId = tenants.find(_.str("id").contains(Fixtures.defaultTenant)).flatMap(_.str("edgeId"))
      created <- api.post(
        "/configuration/tenants",
        Fixtures.tenant(Fixtures.suiteTenant, description = "e2e suite tenant", edgeId = edgeId, securityProfile = Some("standard")),
      )
      _ <- ZIO.fail(RuntimeException(
        s"Could not create the suite tenant '${Fixtures.suiteTenant}' on the standard profile: ${created.status} ${created.body}",
      )).unless(created.status.isSuccess)
      _ <- awaitProfile(api, Fixtures.suiteTenant, "standard")
      // `default` is told where to read a client certificate from by bootstrap (central-admin's
      // mutual TLS), which is what lets the specs register `tls_client_auth` clients; a new
      // tenant is not, and refuses them (RFC 8705 §6.5).
      current <- api.get("/configuration/challenges/challenge-settings", "tenantId" -> Fixtures.suiteTenant).flatMap(_.obj)
      settings <- ZIO.fromOption(current.obj("settings"))
        .orElseFail(RuntimeException(s"The suite tenant has no challenge settings: $current"))
      header <- api.put(
        "/configuration/challenges/challenge-settings",
        Json.Obj(
          settings.fields.filterNot(f => f._1 == "mtlsCertificateHeader" || f._1 == "mtlsCertificateEncoding") :+
            ("mtlsCertificateHeader" -> Json.Str(OAuthClient.mtlsCertificateHeader)) :+
            ("mtlsCertificateEncoding" -> Json.Str("urlEncodedPem")),
        ),
      )
      _ <- ZIO.fail(RuntimeException(s"Could not set the suite tenant's certificate header: ${header.status} ${header.body}"))
        .unless(header.status == Status.NoContent)
      // A tenant is created with its settings and nothing else. `user` is what the registration
      // flows grant -- a client naming a role that does not exist is refused -- and what the
      // account-settings specs sign in as, so it carries the same self-service permission
      // bootstrap gives it in `default`. The endpoints are copied from that permission rather
      // than described again here; edge grants an endpoint by the id a permission names, so
      // the name does not matter -- and it cannot be the seeded `auth-settings:manage`, which
      // the API's own naming rule refuses (bootstrap writes it past that rule).
      seededPermission = "auth-settings:manage"
      accountPermission = "auth_settings:manage"
      seeded <- api.get("/configuration/permissions", "tenantId" -> Fixtures.defaultTenant).flatMap(_.items("permissions"))
      endpointIds = seeded.find(_.str("permission").contains(seededPermission)).map(_.strings("endpointIds")).getOrElse(Set.empty)
      _ <- ZIO.fail(RuntimeException(s"'default' has no permission '$seededPermission' to copy the account endpoints from"))
        .when(endpointIds.isEmpty)
      permissions <- api.get("/configuration/permissions", "tenantId" -> Fixtures.suiteTenant).flatMap(_.items("permissions"))
      _ <- ZIO.unless(permissions.exists(_.str("permission").contains(accountPermission))):
        for
          created <- api.post(
            "/configuration/permissions",
            Fixtures.permission(accountPermission, description = "Manage own account", endpointIds = endpointIds),
          )
          _ <- ZIO.fail(RuntimeException(s"Could not create permission '$accountPermission' in the suite tenant: ${created.status} ${created.body}"))
            .unless(created.status.isSuccess)
        yield ()
      roles <- api.get("/configuration/roles", "tenantId" -> Fixtures.suiteTenant).flatMap(_.items("roles"))
      _ <- ZIO.unless(roles.exists(_.str("id").contains("user"))):
        for
          role <- api.post(
            "/configuration/roles",
            Fixtures.role("user", description = "Self-registered user", permissions = Set(accountPermission)),
          )
          _ <- ZIO.fail(RuntimeException(s"Could not create role 'user' in the suite tenant: ${role.status} ${role.body}"))
            .unless(role.status.isSuccess)
        yield ()
    yield ()

  /** Runs `use` against a tenant of its own, created on `profile`, and deletes it afterwards.
    *
    * The profile is the one thing about it that cannot be edited later, which is why it is a
    * parameter of the tenant rather than something to switch. A tenant on `fapi2` has to be given
    * conformant clients (`private_key_jwt`, DPoP-bound tokens, PAR, an `https` redirect URI);
    * `standard` admits the plain ones. */
  def withTenant[A](api: CentralApi, profile: String)(use: String => Task[A]): Task[A] =
    for
      tenantId <- CentralApi.id(s"e2e-$profile")
      created <- api.post("/configuration/tenants", Fixtures.tenant(tenantId, securityProfile = Some(profile)))
      _ <- ZIO.fail(RuntimeException(s"Could not create tenant '$tenantId': ${created.status} ${created.body}"))
        .unless(created.status.isSuccess)
      // Central answers reads from a cache a Postgres notification refreshes: wait until it
      // shows the new tenant's settings, so the profile asserted on is the one it started on.
      _ <- awaitProfile(api, tenantId, profile)
      result <- use(tenantId).ensuring(api.delete("/configuration/tenants", "tenantId" -> tenantId).ignore)
    yield result

  /** A spec that asserts what only FAPI 2.0 refuses -- a stricter `aud`, a required `jti` or
    * `nbf`, a private-use redirect scheme -- registers its client in a tenant created here, on
    * the profile that `default` is not on. */
  def withFapi2Tenant[A](api: CentralApi)(use: String => Task[A]): Task[A] =
    withTenant(api, "fapi2")(use)

  def profileOf(api: CentralApi, tenantId: String): Task[Option[String]] =
    api.get("/configuration/challenges/challenge-settings", "tenantId" -> tenantId)
      .flatMap(_.obj)
      .map(_.obj("settings").flatMap(_.str("securityProfile")))

  /** Waits for central's cache, which a Postgres notification refreshes, to report `profile`
    * for the tenant -- what a tenant created a moment ago has to wait for before a read of its
    * settings can be trusted. */
  def awaitProfile(api: CentralApi, tenantId: String, profile: String): Task[Unit] =
    profileOf(api, tenantId)
      .repeat(Schedule.spaced(100.millis) *> Schedule.recurUntil[Option[String]](_.contains(profile)))
      .timeout(10.seconds)
      .withClock(Clock.ClockLive)
      .someOrFail(RuntimeException(s"Central does not report '$profile' for tenant '$tenantId'"))
      .unit

  /** Tries to write `profile` over the tenant's stored settings, answering whatever central
    * answers -- a `400` for any profile but the one the tenant was created with. */
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

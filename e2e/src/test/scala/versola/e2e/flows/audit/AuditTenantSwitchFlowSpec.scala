package versola.e2e.flows.audit

import versola.e2e.flows.audit.AuditLogFlowSpec.{awaitRows, kinds, login, page}
import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** A tenant's audit switches as `auth` applies them (the tenant's Security settings, `auditSettings` of its challenge settings): central
  * stores them, `auth` syncs them with the rest of the tenant's settings, and then decides per event.
  *
  * Two tenants of the `standard` profile are made for the case, each with a client that signs in by email and a code and a user. The first
  * has the log switched off, the second keeps it and switches the refreshes on. Both users sign in and refresh. What `auth` stored is read
  * back the way central's per-user report reads it. The writer batches and rests up to a second, so what is read back is waited for, bounded;
  * an absence is shown by the rows of the second tenant, which the same writer queued after the first tenant's events.
  */
object AuditTenantSwitchFlowSpec extends E2ESpec:

  private val settingsPath = "/configuration/challenges/challenge-settings"

  private case class Tenant(id: String, client: Flows.Setup)

  /** A tenant on the `standard` profile with a client (email and a code) and an existing user, removed afterwards. */
  private def withTenant[A](auth: OAuthClient, prefix: String)(use: Tenant => Task[A]): Task[A] =
    SecurityProfiles.withTenant(auth.central, "standard"): tenantId =>
      for
        suffix <- CentralApi.id(prefix)
        clientId = s"$prefix-$suffix"
        email = s"$suffix@example.test"
        registered <- auth.registerClient(
          clientId,
          "Audit switch client",
          Set("http://localhost:3000"),
          tenantId = tenantId,
          allowedScopes = Set("openid", "offline_access"),
          authFlow = Some(Flows.emailOtpAuthFlow),
        ).success
        userId <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()
        result <- use(Tenant(tenantId, Flows.Setup(clientId, registered.secret, "http://localhost:3000", userId, None, Some(email), None, "unused")))
      yield result

  private def patchAudit(auth: OAuthClient, tenantId: String, log: Boolean, tokenRefresh: Boolean): Task[Unit] =
    val body = Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "auditSettings" -> Json.Obj("log" -> Json.Bool(log), "tokenRefresh" -> Json.Bool(tokenRefresh)),
    )
    for
      patched <- auth.central.patch(settingsPath, body)
      _ <- ZIO.fail(RuntimeException(s"patching the audit settings of '$tenantId' answered ${patched.status}: ${patched.body}"))
        .unless(patched.status == Status.NoContent)
    yield ()

  private def signInAndRefresh(auth: OAuthClient, tenant: Tenant): Task[Unit] =
    for
      session <- login(tenant.client, auth)
      _ <- auth.refresh(session.refreshToken, Some(tenant.client.clientId), Some(tenant.client.clientSecret)).success
    yield ()

  def spec = suite("A tenant's audit switches in auth")(
    test(
      "a tenant with the log off has no rows, one that kept it has its rows with its tenant, and refreshes are recorded where they were switched on",
    ) {
      for
        (_, auth) <- setup(Flows.Id.EmailOtp)
        started = Instant.now
        outcome: (String, List[AuditLogFlowSpec.Row], List[AuditLogFlowSpec.Row]) <- withTenant(auth, "audit-sw-off"): off =>
          withTenant(auth, "audit-sw-on"): on =>
            for
              _ <- patchAudit(auth, off.id, log = false, tokenRefresh = true)
              _ <- patchAudit(auth, on.id, log = true, tokenRefresh = true)
              // auth reads the settings from central on its refresh interval; this is the call that makes it read them now
              _ <- auth.syncConfiguration()
              _ <- signInAndRefresh(auth, off)
              _ <- signInAndRefresh(auth, on)
              onRows <- awaitRows(auth, on.client.userId.toString, started)(rows =>
                kinds(rows).contains("login.completed") && rows.exists(r =>
                  r.kind == "token.issued" && r.details.get("grant").flatMap(_.asString).contains("refresh_token"),
                ),
              )
              // written after the first tenant's events by the same writer: when these are in, the first tenant's would be
              offRows <- page(auth, off.client.userId.toString, s"&from=$started&limit=500").map(_.rows)
            yield (on.id, onRows, offRows)
      yield
        val (onTenant, onRows, offRows) = outcome
        assertTrue(
          // nothing the sign-in and the refresh did for a client of the tenant with the log off: no event names that client
          !offRows.exists(_.clientId.isDefined),
          !kinds(offRows).exists(kind => kind.startsWith("login.") || kind.startsWith("token.") || kind.startsWith("session.")),
          // what central did to the user when it registered them names no client and so no tenant: it is recorded whatever any tenant chose
          offRows.forall(row => row.kind == "account.updated" && row.tenantId.isEmpty),
          kinds(onRows).contains("login.completed"),
          onRows.exists(r => r.kind == "token.issued" && r.details.get("grant").flatMap(_.asString).contains("refresh_token")),
          // the rows of a client carry the tenant of that client
          onRows.filter(_.clientId.isDefined).nonEmpty && onRows.filter(_.clientId.isDefined).forall(_.tenantId.contains(onTenant)),
        )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(180.seconds)

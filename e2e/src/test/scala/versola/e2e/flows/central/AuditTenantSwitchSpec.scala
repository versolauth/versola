package versola.e2e.flows.central

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.ast.Json
import zio.test.*

/** A tenant's two audit switches (`auditSettings`: `log` and `tokenRefresh`), which are tenant settings of central and no key of any config:
  * they are stored with the rest of the tenant's challenge settings, patched with the rest, and
  * what an administrator does to a tenant that switched its log off is not in central's audit log.
  *
  * The tenants are made for the case. The rows are read from central's table (the log has no read API for what was done to a client);
  * the writer rests up to a second between batches, so what is read back is waited for, bounded, and the absence of a row is shown by a
  * control row of a tenant with the log on that was written after it.
  */
object AuditTenantSwitchSpec extends ZIOSpec[Client & E2EConfig & EdgeApi & OAuthClient & CentralApi]:

  override val bootstrap: ZLayer[Any, Any, Client & E2EConfig & EdgeApi & OAuthClient & CentralApi] =
    (E2EConfig.live ++ Client.default) >+> (EdgeApi.live ++ OAuthClient.live ++ CentralApi.live)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] = Chunk(TestAspect.withLiveClock)

  private val tenants = "/configuration/tenants"
  private val settingsPath = "/configuration/challenges/challenge-settings"

  private def eventually[A](read: Task[A])(condition: A => Boolean): Task[A] =
    read.repeat(Schedule.spaced(100.millis) *> Schedule.recurUntil(condition)).timeout(20.seconds).someOrElseZIO(read)

  private def withTenant[R, A](prefix: String)(use: (CentralApi, String) => RIO[R, A]): RIO[R & CentralApi, A] =
    for
      central <- ZIO.service[CentralApi]
      tenantId <- CentralApi.id(prefix)
      created <- central.post(tenants, Fixtures.tenant(tenantId, securityProfile = Some("standard")))
      _ <- ZIO.fail(RuntimeException(s"Could not create tenant '$tenantId': ${created.status} ${created.body}")).unless(created.status.isSuccess)
      result <- use(central, tenantId).ensuring(central.delete(tenants, "tenantId" -> tenantId).ignore)
    yield result

  /** The `settings` object of the tenant, once `condition` holds of it (the last one read, on a timeout). */
  private def readSettings(central: CentralApi, tenantId: String)(condition: Json.Obj => Boolean): Task[Json.Obj] =
    eventually(
      central.get(settingsPath, "tenantId" -> tenantId).flatMap(_.obj).map(_.obj("settings").getOrElse(Json.Obj())),
    )(settings => settings.fields.nonEmpty && condition(settings))

  private def patch(central: CentralApi, tenantId: String, members: (String, Json)*) =
    central.patch(settingsPath, Json.Obj(Chunk.from(("tenantId" -> Json.Str(tenantId)) +: members)))

  private def audit(log: Boolean, tokenRefresh: Boolean): Json =
    Json.Obj("log" -> Json.Bool(log), "tokenRefresh" -> Json.Bool(tokenRefresh))

  private def serviceBody(tenantId: String, id: String) =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "id" -> Json.Str(id),
      "clientName" -> Fixtures.text("Audit switch service"),
      "allowedScopes" -> Json.Arr(Json.Str("openid")),
      "permissions" -> Json.Arr(),
      "accessTokenTtl" -> Json.Num(3600),
      "dpopBoundAccessTokens" -> Json.Bool(false),
      "dpopSigningAlgs" -> Json.Arr(),
      "authMethod" -> Json.Str("client_secret"),
      "certificateBoundAccessTokens" -> Json.Bool(false),
      "estEnrollment" -> Json.Bool(false),
    )

  private def rows(targetId: String)(ready: List[CentralDatabase.AuditRow] => Boolean) =
    eventually(CentralDatabase.auditEvents(targetId))(ready)

  def spec = suite("A tenant's audit switches")(
    test("a tenant starts with the log on and the refreshes off, and a patch replaces the audit settings") {
      withTenant("e2e-audit-sw"): (central, tenantId) =>
        for
          initial <- readSettings(central, tenantId)(_ => true)
          refreshOn <- patch(central, tenantId, "auditSettings" -> audit(log = true, tokenRefresh = true))
          afterRefresh <- readSettings(central, tenantId)(_.obj("auditSettings").flatMap(_.bool("tokenRefresh")).contains(true))
          logOff <- patch(central, tenantId, "auditSettings" -> audit(log = false, tokenRefresh = true))
          afterLog <- readSettings(central, tenantId)(_.obj("auditSettings").flatMap(_.bool("log")).contains(false))
          cleared <- patch(central, tenantId, "auditSettings" -> Json.Null)
          halved <- patch(central, tenantId, "auditSettings" -> Json.Obj("log" -> Json.Bool(false)))
        yield assertTrue(
          initial.obj("auditSettings").flatMap(_.bool("log")).contains(true),
          initial.obj("auditSettings").flatMap(_.bool("tokenRefresh")).contains(false),
          refreshOn.status == Status.NoContent,
          afterRefresh.obj("auditSettings").flatMap(_.bool("log")).contains(true),
          logOff.status == Status.NoContent,
          afterLog.obj("auditSettings").flatMap(_.bool("tokenRefresh")).contains(true),
          // the settings are on or off, never cleared, and both switches are named
          cleared.status == Status.BadRequest,
          halved.status == Status.BadRequest,
        )
    },
    test("what an administrator does in a tenant that switched its log off is not recorded; the same in a tenant that kept it is") {
      withTenant("e2e-audit-off"): (central, offTenant) =>
        withTenant("e2e-audit-on"): (_, onTenant) =>
          for
            c <- ZIO.service[E2EConfig]
            auth <- ZIO.service[OAuthClient]
            edge <- ZIO.service[EdgeApi]
            session <- ProvisionerCredential.session(auth, c, List("resource://central"))
            _ <- patch(central, offTenant, "auditSettings" -> audit(log = false, tokenRefresh = false))
            _ <- readSettings(central, offTenant)(_.obj("auditSettings").flatMap(_.bool("log")).contains(false))
            offClient <- CentralApi.id("audit-off-client")
            onClient <- CentralApi.id("audit-on-client")
            offCreated <- edge.proxyDpop(Method.POST, "central", "/configuration/clients/service", session, Some(serviceBody(offTenant, offClient)))
            // written after the one above, by the same writer: when this is in the table the other one would be
            onCreated <- edge.proxyDpop(Method.POST, "central", "/configuration/clients/service", session, Some(serviceBody(onTenant, onClient)))
            control <- rows(onClient)(_.exists(_.kind == "admin.client.create"))
            off <- CentralDatabase.auditEvents(offClient)
          yield assertTrue(
            offCreated.status == Status.Created,
            onCreated.status == Status.Created,
            control.exists(_.kind == "admin.client.create"),
            off.isEmpty,
          )
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)

package versola.audit

import versola.oauth.client.OAuthConfigurationServiceSpec
import versola.oauth.client.model.{AuditSettings, ClientId, TenantId}
import versola.util.audit.*
import versola.util.http.{NoopTracing, Observability}
import versola.util.partitions.{PartitionReport, TableOwnership}
import versola.util.{SecureRandom, UnitSpecBase}
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate}

/** The tenant's audit switches as auth applies them: the tenant of an event is the one of the client it names, the log of a tenant that
  * switched it off is not written, and what belongs to no tenant is written. Built from the real writer, the real configuration cache and
  * a repository that keeps what it is given. */
object AuthAuditTenantsSpec extends UnitSpecBase:

  private val acme = TenantId("acme")
  private val globex = TenantId("globex")
  private val acmeClient = ClientId("acme-client")
  private val globexClient = ClientId("globex-client")

  private val sizing = AuditEvents.Sizing(queueCapacity = 100, batchSize = 10, flushInterval = 1.second, maintenanceInterval = 1.hour)

  private class Keeping(stored: Ref[List[AuditEvent]]) extends AuditEventRepository:
    override def insertBatch(events: List[AuditEvent]): Task[Unit] = stored.update(_ ++ events)

    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
      ZIO.succeed(PartitionReport(movedFromDefault = 0L, daysLeftInDefault = Nil, created = 0, dropped = 0))

    override def partitionDaysAhead(today: LocalDate): Task[Int] = ZIO.succeed(3)

    override def pruneDefault(before: Instant): Task[Long] = ZIO.succeed(0L)

    override def ownership: Task[TableOwnership] = ZIO.succeed(TableOwnership(owner = "auth", role = "auth", mayAlter = true))

  private def draft(kind: AuditKind) =
    AuditDraft(kind = kind, outcome = AuditOutcome.success, reason = None, actor = None, userId = None, target = None, details = AuditDetails.empty)

  /** Two tenants: `acme` has the switches given, `globex` the defaults. Records through the real writer what `record` is given, closes the
    * writer (which writes what it holds) and says what was written. */
  private def written(acmeLog: Boolean, acmeRefresh: Boolean)(record: AuditEvents => UIO[Unit]): UIO[List[AuditEvent]] =
    val acmeSettings = OAuthConfigurationServiceSpec.challengeSettings.copy(
      tenantId = acme,
      auditSettings = Some(AuditSettings(log = acmeLog, tokenRefresh = acmeRefresh)),
    )
    val globexSettings = OAuthConfigurationServiceSpec.challengeSettings.copy(tenantId = globex)
    val clients = Map(
      acmeClient -> OAuthConfigurationServiceSpec.privateClient.copy(id = acmeClient, tenantId = acme),
      globexClient -> OAuthConfigurationServiceSpec.privateClient.copy(id = globexClient, tenantId = globex),
    )
    for
      stored <- Ref.make(List.empty[AuditEvent])
      configuration <- OAuthConfigurationServiceSpec.makeEnv(clients = clients, challengeSettingsVec = Vector(acmeSettings, globexSettings))
      _ <- ZIO.scoped:
        AuditEvents.liveWith(sizing)(AuthAudit.source, "node-1", AuthAudit.catalog, "auth_audit_event").build
          .map(_.get[AuditEvents])
          .provideSome[Scope](
            ZLayer.succeed[AuditEventRepository](Keeping(stored)),
            ZLayer.succeed(AuditEvents.Settings.standard),
            ZLayer.succeed[AuditTenants](AuthAuditTenants.Impl(configuration)),
            NoopTracing.layer,
            SecureRandom.live,
          )
          .flatMap(record)
      all <- stored.get
    yield all

  private def asClient(id: String, kind: AuditKind): AuditEvents => UIO[Unit] =
    events => Observability.setClientId(id) *> events.recordBestEffort(draft(kind))

  def spec = suite("AuthAuditTenants")(
    test("an event of a client of a tenant that switched its log off is not written") {
      for rows <- written(acmeLog = false, acmeRefresh = false)(asClient(acmeClient, AuthAuditKinds.LoginCompleted))
      yield assertTrue(rows.isEmpty)
    },
    test("an event of a client of a tenant that keeps its log is written, and carries the tenant") {
      for rows <- written(acmeLog = false, acmeRefresh = false)(asClient(globexClient, AuthAuditKinds.LoginCompleted))
      yield assertTrue(rows.map(r => (r.kind, r.tenantId, r.clientId)) == List((
        AuthAuditKinds.LoginCompleted,
        Some("globex"),
        Some("globex-client"),
      )))
    },
    test("an event whose only client is the actor is the tenant's too") {
      val byActor = draft(AuthAuditKinds.TokenRevoked).copy(actor = Some((AuthActors.client, Some(acmeClient))))
      for rows <- written(acmeLog = false, acmeRefresh = false)(_.recordBestEffort(byActor))
      yield assertTrue(rows.isEmpty)
    },
    test("an event that names no client belongs to no tenant and is written, whichever tenants switched off") {
      for rows <- written(acmeLog = false, acmeRefresh = false)(_.recordBestEffort(draft(AuthAuditKinds.CertEnrolled)))
      yield assertTrue(rows.map(r => (r.kind, r.tenantId)) == List((AuthAuditKinds.CertEnrolled, None)))
    },
    test("an event of a client auth does not know belongs to no tenant and is written") {
      for rows <- written(acmeLog = false, acmeRefresh = false)(asClient("nobody", AuthAuditKinds.ClientAuthFailed))
      yield assertTrue(rows.map(r => (r.kind, r.tenantId, r.clientId)) == List((AuthAuditKinds.ClientAuthFailed, None, Some("nobody"))))
    },
    test("a tenant that switched its log off records nothing, even with the refreshes switched on") {
      for rows <- written(acmeLog = false, acmeRefresh = true)(asClient(acmeClient, AuthAuditKinds.TokenIssued))
      yield assertTrue(rows.isEmpty)
    },
  )

package versola.central.audit

import versola.central.configuration.challenges.ChallengeSettingsService
import versola.central.configuration.tenants.TenantId
import versola.util.audit.{AuditEvent, AuditTenants}
import zio.*

/** Whether a tenant keeps an audit log, from its Security settings (`auditLog` of its challenge settings): central's own cache of them, so that a
  * change of the switch applies at once. */
object CentralAuditTenants:
  val live: URLayer[ChallengeSettingsService, AuditTenants] = ZLayer.fromFunction(Impl(_))

  class Impl(settings: ChallengeSettingsService) extends AuditTenants:
    // Central's events carry the tenant of the administrator's token, which says nothing of the tenant they are about: the hook chooses it.
    override def tenantOf(event: AuditEvent): UIO[Option[String]] = ZIO.none

    /** A tenant with no settings keeps a log. A lookup that fails keeps it too: the log is the record of what administrators did, and
      * a tenant's change that cannot be read is not a reason to lose an entry. */
    override def recording(tenantId: String): UIO[Boolean] =
      settings.getSettings(TenantId(tenantId)).map(_.forall(_.auditSettings.log)).catchAllCause: cause =>
        ZIO.logWarningCause(s"the audit switch of tenant '$tenantId' could not be read, so its events are recorded", cause).as(true)

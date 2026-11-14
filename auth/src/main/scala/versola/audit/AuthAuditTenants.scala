package versola.audit

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.audit.{AuditEvent, AuditTenants}
import zio.*

/** Which tenant an event of auth belongs to, and whether that tenant keeps an audit log: the tenant's Security settings, as auth's configuration cache
  * holds them (see [[OAuthConfigurationService.auditLog]]).
  *
  * A request does not carry its tenant in the audit context, so the tenant of an event is the one of the client it names (the `auth` log context's
  * client, else the client that acted). An event that names no client, or one auth does not know, belongs to no tenant and is recorded. */
object AuthAuditTenants:
  val live: URLayer[OAuthConfigurationService, AuditTenants] = ZLayer.fromFunction(Impl(_))

  class Impl(configuration: OAuthConfigurationService) extends AuditTenants:
    override def tenantOf(event: AuditEvent): UIO[Option[String]] =
      val client = event.clientId.orElse(Option.when(event.actorType == AuthActors.client)(event.actorId).flatten)
      ZIO.foreach(client.filter(_.nonEmpty))(id => configuration.find(ClientId(id))).map(_.flatten.map(_.tenantId.toString))

    override def recording(tenantId: String): UIO[Boolean] = configuration.auditLog(TenantId(tenantId))

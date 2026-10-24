package versola.central.antifraud

import versola.central.CentralConfig
import versola.central.users.AuthClient
import versola.util.PageLimit
import zio.{IO, ZIO, ZLayer}

import java.util.UUID

/** The antifraud lists as the admin console sees them. The lists are stored and enforced by auth, which validates the
  * entries and decides what an actor may do; central says who the actor is.
  *
  * An admin of the tenant that carries central's own roles ([[CentralConfig.defaultTenantId]]) is an operator: global
  * entries, other tenants' entries, the audit. An admin of any other tenant is limited to that tenant's entries.
  */
trait AntifraudListService:
  def create(entry: NewListEntry, caller: AdminCaller): IO[AntifraudListError | Throwable, ListEntryResponse]

  def preview(entry: NewListEntry, caller: AdminCaller): IO[AntifraudListError | Throwable, PreviewResponse]

  def list(query: ListEntriesQuery, caller: AdminCaller): IO[AntifraudListError | Throwable, ListEntriesResponse]

  def delete(id: UUID, caller: AdminCaller): IO[AntifraudListError | Throwable, Unit]

  def audit(entryId: Option[UUID], limit: Option[PageLimit], caller: AdminCaller): IO[AntifraudListError | Throwable, AuditResponse]

object AntifraudListService:
  val live: ZLayer[AuthClient, Nothing, AntifraudListService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(authClient: AuthClient) extends AntifraudListService:
    override def create(entry: NewListEntry, caller: AdminCaller): IO[AntifraudListError | Throwable, ListEntryResponse] =
      actorOf(caller).flatMap(authClient.createAntifraudListEntry(entry, _))

    override def preview(entry: NewListEntry, caller: AdminCaller): IO[AntifraudListError | Throwable, PreviewResponse] =
      actorOf(caller).flatMap(authClient.previewAntifraudListEntry(entry, _))

    override def list(query: ListEntriesQuery, caller: AdminCaller): IO[AntifraudListError | Throwable, ListEntriesResponse] =
      actorOf(caller).flatMap(authClient.listAntifraudListEntries(query, _)).map(ListEntriesResponse(_))

    override def delete(id: UUID, caller: AdminCaller): IO[AntifraudListError | Throwable, Unit] =
      actorOf(caller).flatMap(authClient.deleteAntifraudListEntry(id, _))

    override def audit(entryId: Option[UUID], limit: Option[PageLimit], caller: AdminCaller): IO[AntifraudListError | Throwable, AuditResponse] =
      actorOf(caller).flatMap(authClient.antifraudListAudit(entryId, limit, _)).map(AuditResponse(_))

    private def actorOf(caller: AdminCaller): IO[AntifraudListError, AntifraudListActor] =
      (caller.subject.filter(_.nonEmpty), caller.tenant) match
        case (Some(name), Some(tenant)) =>
          ZIO.succeed(AntifraudListActor(name = name, tenantId = Option.unless(tenant == CentralConfig.defaultTenantId)(tenant)))
        case _ => ZIO.fail(AntifraudListError.NoCaller)

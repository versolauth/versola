package versola.central.antifraud

import versola.central.users.AuthClient
import versola.util.PageLimit
import zio.{IO, ZLayer}

import java.util.UUID

/** The antifraud lists as the admin console sees them. The lists are stored and enforced by auth, which validates the
  * entries; central passes the admin's request on with who the admin is (`userId`, from edge) and whose scope it is made in
  * (`caller`, from the route: see [[AntifraudCaller]]), which auth enforces. */
trait AntifraudListService:
  def create(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, ListEntryResponse]

  def preview(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, PreviewResponse]

  def list(query: ListEntriesQuery, caller: AntifraudCaller): IO[AntifraudListError | Throwable, ListEntriesResponse]

  def delete(request: DeleteListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, Unit]

  def audit(entryId: Option[UUID], limit: Option[PageLimit], caller: AntifraudCaller): IO[AntifraudListError | Throwable, AuditResponse]

object AntifraudListService:
  val live: ZLayer[AuthClient, Nothing, AntifraudListService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(authClient: AuthClient) extends AntifraudListService:
    override def create(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, ListEntryResponse] =
      authClient.createAntifraudListEntry(request, caller)

    override def preview(request: NewListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, PreviewResponse] =
      authClient.previewAntifraudListEntry(request, caller)

    override def list(query: ListEntriesQuery, caller: AntifraudCaller): IO[AntifraudListError | Throwable, ListEntriesResponse] =
      authClient.listAntifraudListEntries(query, caller).map(ListEntriesResponse(_))

    override def delete(request: DeleteListEntryRequest, caller: AntifraudCaller): IO[AntifraudListError | Throwable, Unit] =
      authClient.deleteAntifraudListEntry(request, caller)

    override def audit(entryId: Option[UUID], limit: Option[PageLimit], caller: AntifraudCaller): IO[AntifraudListError | Throwable, AuditResponse] =
      authClient.antifraudListAudit(entryId, limit, caller).map(AuditResponse(_))

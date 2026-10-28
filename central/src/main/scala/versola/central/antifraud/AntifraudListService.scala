package versola.central.antifraud

import versola.central.users.{AuthClient, UserId}
import versola.util.PageLimit
import zio.{IO, ZLayer}

import java.util.UUID

/** The antifraud lists as the admin console sees them. The lists are stored and enforced by auth, which validates the
  * entries; central passes the admin's request on with who the admin is (`userId`, from edge). */
trait AntifraudListService:
  def create(request: NewListEntryRequest): IO[AntifraudListError | Throwable, ListEntryResponse]

  def preview(request: NewListEntryRequest): IO[AntifraudListError | Throwable, PreviewResponse]

  def list(query: ListEntriesQuery, userId: UserId): IO[AntifraudListError | Throwable, ListEntriesResponse]

  def delete(request: DeleteListEntryRequest): IO[AntifraudListError | Throwable, Unit]

  def audit(entryId: Option[UUID], limit: Option[PageLimit], userId: UserId): IO[AntifraudListError | Throwable, AuditResponse]

object AntifraudListService:
  val live: ZLayer[AuthClient, Nothing, AntifraudListService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(authClient: AuthClient) extends AntifraudListService:
    override def create(request: NewListEntryRequest): IO[AntifraudListError | Throwable, ListEntryResponse] =
      authClient.createAntifraudListEntry(request)

    override def preview(request: NewListEntryRequest): IO[AntifraudListError | Throwable, PreviewResponse] =
      authClient.previewAntifraudListEntry(request)

    override def list(query: ListEntriesQuery, userId: UserId): IO[AntifraudListError | Throwable, ListEntriesResponse] =
      authClient.listAntifraudListEntries(query, userId).map(ListEntriesResponse(_))

    override def delete(request: DeleteListEntryRequest): IO[AntifraudListError | Throwable, Unit] =
      authClient.deleteAntifraudListEntry(request)

    override def audit(entryId: Option[UUID], limit: Option[PageLimit], userId: UserId): IO[AntifraudListError | Throwable, AuditResponse] =
      authClient.antifraudListAudit(entryId, limit, userId).map(AuditResponse(_))

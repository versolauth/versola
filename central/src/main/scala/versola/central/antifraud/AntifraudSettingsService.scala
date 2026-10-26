package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import versola.central.users.{AuthClient, UserId}
import zio.{IO, ZLayer}

/** The antifraud settings as the admin console sees them: the thresholds, weights, shadow rules, limits on codes sent and
  * timings that are tuned during an attack. They are stored and applied by auth, which validates them; central passes the
  * admin's request on with who the admin is (`userId`, from edge). Auth's refusals are [[AntifraudListError]]s, as for the
  * lists. */
trait AntifraudSettingsService:
  def get(tenantId: Option[TenantId], userId: UserId): IO[AntifraudListError | Throwable, AntifraudSettingsResponse]

  def put(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, AntifraudSettingsResponse]

  def delete(request: DeleteAntifraudSettingsRequest): IO[AntifraudListError | Throwable, Unit]

  def audit(query: AntifraudSettingsAuditQuery, userId: UserId): IO[AntifraudListError | Throwable, AntifraudSettingsAuditResponse]

object AntifraudSettingsService:
  val live: ZLayer[AuthClient, Nothing, AntifraudSettingsService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(authClient: AuthClient) extends AntifraudSettingsService:
    override def get(tenantId: Option[TenantId], userId: UserId): IO[AntifraudListError | Throwable, AntifraudSettingsResponse] =
      authClient.getAntifraudSettings(tenantId, userId)

    override def put(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, AntifraudSettingsResponse] =
      authClient.putAntifraudSettings(request)

    override def delete(request: DeleteAntifraudSettingsRequest): IO[AntifraudListError | Throwable, Unit] =
      authClient.deleteAntifraudSettings(request)

    override def audit(query: AntifraudSettingsAuditQuery, userId: UserId): IO[AntifraudListError | Throwable, AntifraudSettingsAuditResponse] =
      authClient.antifraudSettingsAudit(query, userId).map(AntifraudSettingsAuditResponse(_))

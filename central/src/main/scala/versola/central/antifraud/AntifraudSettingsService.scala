package versola.central.antifraud

import versola.central.users.{AuthClient, UserId}
import zio.{IO, ZLayer}

/** The antifraud settings as the admin console sees them: the thresholds, weights, shadow rules, limits on codes sent and
  * proof-of-work difficulty that are tuned during an attack. They are stored and applied by auth, which validates them;
  * central passes the admin's request on with who the admin is (`userId`, from edge). Auth's refusals are
  * [[AntifraudListError]]s, as for the lists. */
trait AntifraudSettingsService:
  def get(userId: UserId): IO[AntifraudListError | Throwable, AntifraudSettings]

  def put(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, AntifraudSettings]

  /** Puts the seeded values back (everything off). */
  def reset(request: DeleteAntifraudSettingsRequest): IO[AntifraudListError | Throwable, Unit]

object AntifraudSettingsService:
  val live: ZLayer[AuthClient, Nothing, AntifraudSettingsService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(authClient: AuthClient) extends AntifraudSettingsService:
    override def get(userId: UserId): IO[AntifraudListError | Throwable, AntifraudSettings] =
      authClient.getAntifraudSettings(userId)

    override def put(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, AntifraudSettings] =
      authClient.putAntifraudSettings(request)

    override def reset(request: DeleteAntifraudSettingsRequest): IO[AntifraudListError | Throwable, Unit] =
      authClient.deleteAntifraudSettings(request)

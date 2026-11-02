package versola.central.antifraud

import versola.central.users.AuthClient
import zio.{IO, ZLayer}

/** The antifraud settings as the admin console sees them: the thresholds, weights, shadow rules, limits on codes sent and
  * proof-of-work difficulty that are tuned during an attack. They are stored and applied by auth, which validates them;
  * central passes the admin's request on with who the admin is (`userId`, from edge). Auth's refusals are
  * [[AntifraudListError]]s, as for the lists, and so is a change made on a version the settings have left
  * ([[AntifraudListError.StaleVersion]]). */
trait AntifraudSettingsService:
  def get: IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** `request.version` is the version the change is made on, when one was named; without it the settings are replaced. */
  def put(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** Changes the settings by a merge patch, on `request.version` if the caller named one; answers all of them. */
  def patch(request: PatchAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** Puts everything off, on `expectedVersion` if the caller named one. */
  def reset(request: DeleteAntifraudSettingsRequest, expectedVersion: Option[Long]): IO[AntifraudListError | Throwable, Unit]

object AntifraudSettingsService:
  val live: ZLayer[AuthClient, Nothing, AntifraudSettingsService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(authClient: AuthClient) extends AntifraudSettingsService:
    override def get: IO[AntifraudListError | Throwable, VersionedAntifraudSettings] =
      authClient.getAntifraudSettings

    override def put(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings] =
      authClient.putAntifraudSettings(request)

    override def patch(request: PatchAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings] =
      authClient.patchAntifraudSettings(request)

    override def reset(request: DeleteAntifraudSettingsRequest, expectedVersion: Option[Long]): IO[AntifraudListError | Throwable, Unit] =
      authClient.deleteAntifraudSettings(request, expectedVersion)

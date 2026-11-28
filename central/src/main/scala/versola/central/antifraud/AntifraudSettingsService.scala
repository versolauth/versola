package versola.central.antifraud

import versola.central.users.AuthClient
import zio.{Clock, IO, ZIO, ZLayer}

/** The antifraud settings: the thresholds, weights, shadow rules, limits on codes sent and proof-of-work difficulty that
  * are tuned during an attack. Central stores them, once for the deployment, and every cell copies them (see
  * [[AntifraudPolicyController]]); auth says whether a change is valid before it is stored, and what "everything off" is,
  * because the rules and the defaults of a setting belong to the engine that applies it. So a change, and a read where
  * nothing is stored yet, need the configured `auth` to answer; a read of stored settings does not.
  *
  * A refusal is an [[AntifraudListError]], as for the lists: `Invalid` is auth's, and a change made on a version the
  * settings have left is [[AntifraudListError.StaleVersion]]. */
trait AntifraudSettingsService:
  /** The settings as stored; everything off at version 0 where there are none. */
  def get: IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** `request.version` is the version the change is made on, when one was named; without it the settings are replaced. */
  def put(request: PutAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** Changes the settings by a patch, on `request.version` if the caller named one; answers all of them. Without one a
    * change made by someone else between the read and the write makes the patch be made again on what they wrote, once. */
  def patch(request: PatchAntifraudSettingsRequest): IO[AntifraudListError | Throwable, VersionedAntifraudSettings]

  /** Puts everything off, on `expectedVersion` if the caller named one; it moves the version too. */
  def reset(request: DeleteAntifraudSettingsRequest, expectedVersion: Option[Long]): IO[AntifraudListError | Throwable, Unit]

  /** What a cell compares its copy with. */
  def state: IO[Throwable, PolicyState]

object AntifraudSettingsService:
  val live: ZLayer[AntifraudSettingsRepository & AntifraudListRepository & AuthClient, Nothing, AntifraudSettingsService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(repository: AntifraudSettingsRepository, lists: AntifraudListRepository, authClient: AuthClient) extends AntifraudSettingsService:
    private type Failure = AntifraudListError | Throwable

    private val off: IO[Failure, AntifraudSettings] = authClient.checkAntifraudSettings(SettingsCheck(settings = None, patch = None))

    override def get: IO[Failure, VersionedAntifraudSettings] =
      repository.find.flatMap:
        case Some(stored) => ZIO.succeed(stored)
        case None => off.map(VersionedAntifraudSettings(_, 0L))

    override def put(request: PutAntifraudSettingsRequest): IO[Failure, VersionedAntifraudSettings] =
      authClient.checkAntifraudSettings(SettingsCheck(settings = Some(request.settings), patch = None)).flatMap: settings =>
        write(settings, actor = request.userId.toString, expectedVersion = request.version)

    override def patch(request: PatchAntifraudSettingsRequest): IO[Failure, VersionedAntifraudSettings] =
      def attempt(retriesLeft: Int): IO[Failure, VersionedAntifraudSettings] =
        for
          stored <- repository.find
          version = stored.fold(0L)(_.version)
          _ <- ZIO.fail(stale(version)).when(request.version.exists(_ != version))
          settings <- authClient.checkAntifraudSettings(SettingsCheck(settings = stored.map(_.settings), patch = Some(request.patch)))
          // The write is made on the version the patch was made from, so a change in between is not overwritten.
          written <- write(settings, actor = request.userId.toString, expectedVersion = Some(version)).catchSome:
            case _: AntifraudListError.StaleVersion if request.version.isEmpty && retriesLeft > 0 => attempt(retriesLeft - 1)
        yield written
      attempt(retriesLeft = 1)

    override def reset(request: DeleteAntifraudSettingsRequest, expectedVersion: Option[Long]): IO[Failure, Unit] =
      off.flatMap(write(_, actor = request.userId.toString, expectedVersion = expectedVersion)).unit

    override def state: IO[Throwable, PolicyState] =
      for
        stored <- repository.find
        listsVersion <- lists.version
      yield PolicyState(settingsVersion = stored.fold(0L)(_.version), settings = stored.map(_.settings), listsVersion = listsVersion)

    private def write(settings: AntifraudSettings, actor: String, expectedVersion: Option[Long]): IO[Failure, VersionedAntifraudSettings] =
      for
        now <- Clock.instant
        written <- repository.put(settings = settings, actor = actor, at = now, expectedVersion = expectedVersion)
        versioned <- written match
          case Some(version) => ZIO.succeed(VersionedAntifraudSettings(settings, version))
          case None => repository.find.flatMap(current => ZIO.fail(stale(current.fold(0L)(_.version))))
      yield versioned

    private def stale(current: Long): AntifraudListError.StaleVersion =
      AntifraudListError.StaleVersion(s"the settings are at version $current", Some(current))

package versola.edge

import versola.util.ReloadingCache
import zio.{Scope, Task, UIO, ZIO, ZLayer}

/** The console settings central currently holds (see [[EdgeConsoleSettings]]), cached on `configurationCacheRefreshInterval` like
  * everything else the edge syncs, and served from the configuration snapshot while central is unreachable. Read when `/permissions/me`
  * answers; nothing else in request handling reads it.
  */
trait ConsoleSettingsService:
  /** The time zone the console shows times in for an administrator with no zone of their own, `None` when none is set. */
  def defaultTimeZone: UIO[Option[String]]

  /** Reloads from central now, instead of waiting for the refresh interval (the non-prod `/service/configuration/sync`). */
  def refreshNow: Task[Unit]

object ConsoleSettingsService:
  def live: ZLayer[ConsoleSettingsSyncClient & Scope & EdgeConfig, Throwable, ConsoleSettingsService] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[EdgeConsoleSettings](config.configurationCacheRefreshInterval, fromSnapshot = true),
        )
      ) ++
        ZLayer.service[ConsoleSettingsSyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[EdgeConsoleSettings],
      source: ConsoleSettingsSyncClient,
  ) extends ConsoleSettingsService:
    override def defaultTimeZone: UIO[Option[String]] = cache.get.map(_.uiTimeZone)

    override def refreshNow: Task[Unit] = source.getAll.flatMap(cache.set)

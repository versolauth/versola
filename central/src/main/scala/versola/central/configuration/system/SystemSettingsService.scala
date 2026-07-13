package versola.central.configuration.system

import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZLayer}

trait SystemSettingsService:
  def getSettings: Task[SystemSettingsRecord]
  def upsertSettings(record: SystemSettingsRecord): Task[Unit]
  def sync(): Task[Unit]

object SystemSettingsService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[SystemSettingsRepository & Scope, Throwable, SystemSettingsService] =
    ZLayer(ReloadingCache.make[SystemSettingsRecord](schedule))
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[SystemSettingsRecord],
      repository: SystemSettingsRepository,
  ) extends SystemSettingsService:

    override def getSettings: Task[SystemSettingsRecord] =
      cache.get

    override def upsertSettings(record: SystemSettingsRecord): Task[Unit] =
      repository.upsert(record)

    override def sync(): Task[Unit] =
      repository.getAll.flatMap(cache.set)

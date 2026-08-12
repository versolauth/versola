package versola.central.configuration.challenges

import versola.central.CentralConfig
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

trait ChallengeSettingsService:
  def getSettings(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]]
  def getAllSettings: Task[Vector[ChallengeSettingsRecord]]
  def upsertSettings(record: ChallengeSettingsRecord): Task[Unit]
  def sync(event: SyncEvent.ChallengeSettingsUpdated): Task[Unit]

object ChallengeSettingsService:
  def live: ZLayer[ChallengeSettingsRepository & Scope & CentralConfig, Throwable, ChallengeSettingsService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[ChallengeSettingsRecord]](Schedule.spaced(config.configurationCacheRefreshInterval)),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Vector[ChallengeSettingsRecord]],
      repository: ChallengeSettingsRepository,
  ) extends ChallengeSettingsService:

    override def getSettings(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]] =
      cache.get.map(_.find(_.tenantId == tenantId))

    override def getAllSettings: Task[Vector[ChallengeSettingsRecord]] =
      cache.get

    override def upsertSettings(record: ChallengeSettingsRecord): Task[Unit] =
      repository.upsert(record)

    override def sync(event: SyncEvent.ChallengeSettingsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.findByTenant(event.tenantId),
      )

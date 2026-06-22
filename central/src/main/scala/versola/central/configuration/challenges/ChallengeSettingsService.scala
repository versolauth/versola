package versola.central.configuration.challenges

import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZLayer}

trait ChallengeSettingsService:
  def getSettings(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]]
  def getAllSettings: Task[Vector[ChallengeSettingsRecord]]
  def upsertSettings(record: ChallengeSettingsRecord): Task[Unit]
  def sync(event: SyncEvent.ChallengeSettingsUpdated): Task[Unit]

object ChallengeSettingsService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[ChallengeSettingsRepository & Scope, Throwable, ChallengeSettingsService] =
    ZLayer(ReloadingCache.make[Vector[ChallengeSettingsRecord]](schedule))
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

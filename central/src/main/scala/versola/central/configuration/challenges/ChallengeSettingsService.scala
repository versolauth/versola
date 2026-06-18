package versola.central.configuration.challenges

import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZLayer}

trait ChallengeSettingsService:
  def getSettings(tenantId: TenantId): Task[ChallengeSettingsRecord]
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

    override def getSettings(tenantId: TenantId): Task[ChallengeSettingsRecord] =
      cache.get.map(
        _.find(_.tenantId == tenantId)
          .getOrElse(ChallengeSettingsRecord(tenantId, Nil, None, SubmissionLimits.empty, otpLength = 6, otpResendAfter = 60)),
      )

    override def getAllSettings: Task[Vector[ChallengeSettingsRecord]] =
      cache.get

    override def upsertSettings(record: ChallengeSettingsRecord): Task[Unit] =
      for
        _ <- repository.upsert(record)
        all <- repository.getAll
        _ <- cache.set(all)
      yield ()

    override def sync(event: SyncEvent.ChallengeSettingsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.findByTenant(event.tenantId),
      )

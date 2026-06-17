package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZLayer}

trait PhoneChallengeService:
  def getSettings(tenantId: TenantId): Task[PhoneSettingsRecord]
  def getAllSettings: Task[Vector[PhoneSettingsRecord]]
  def upsertSettings(record: PhoneSettingsRecord): Task[Unit]

object PhoneChallengeService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[PhoneChallengeRepository & Scope, Throwable, PhoneChallengeService] =
    ZLayer(ReloadingCache.make[Vector[PhoneSettingsRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Vector[PhoneSettingsRecord]],
      repository: PhoneChallengeRepository,
  ) extends PhoneChallengeService:

    override def getSettings(tenantId: TenantId): Task[PhoneSettingsRecord] =
      cache.get.map(
        _.find(_.tenantId == tenantId)
          .getOrElse(PhoneSettingsRecord(tenantId, Nil)),
      )

    override def getAllSettings: Task[Vector[PhoneSettingsRecord]] =
      cache.get

    override def upsertSettings(record: PhoneSettingsRecord): Task[Unit] =
      for
        _ <- repository.upsert(record)
        all <- repository.getAll
        _ <- cache.set(all)
      yield ()

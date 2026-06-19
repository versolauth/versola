package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.Task

trait ChallengeSettingsRepository extends CacheSource[Vector[ChallengeSettingsRecord]]:
  def getAll: Task[Vector[ChallengeSettingsRecord]]
  def findByTenant(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]]
  def upsert(record: ChallengeSettingsRecord): Task[Unit]

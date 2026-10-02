package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.Task

trait ChallengeSettingsRepository extends CacheSource[Vector[ChallengeSettingsRecord]]:
  def getAll: Task[Vector[ChallengeSettingsRecord]]
  def findByTenant(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]]

  /** Writes `record`, unless the tenant already has settings under another security profile,
    * which is fixed when the tenant is created.
    *
    * @return whether the settings were written -- `false` for a tenant whose stored profile is
    *   not `record`'s, which is left as it was.
    */
  def upsert(record: ChallengeSettingsRecord): Task[Boolean]

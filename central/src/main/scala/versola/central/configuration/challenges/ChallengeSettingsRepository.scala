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

  /** Applies `change` to the tenant's stored settings and writes the result, as one transaction that holds the row
    * locked from the read to the write: two patches of different members, running at once, both land, instead of the
    * second one writing back the row as it read it before the first.
    *
    * `change` runs inside the transaction on the locked row and must be pure and quick. A `Left` from it writes nothing.
    * The tenant's security profile is not written: it is fixed when the tenant is created.
    *
    * @return `None` when the tenant has no settings, otherwise what `change` answered.
    */
  def patch(
      tenantId: TenantId,
      change: ChallengeSettingsRecord => Either[String, ChallengeSettingsRecord],
  ): Task[Option[Either[String, ChallengeSettingsRecord]]]

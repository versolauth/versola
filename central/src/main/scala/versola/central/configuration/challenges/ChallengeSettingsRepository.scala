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

  /** Merges `patch` into the tenant's stored settings and writes the result, as one transaction that holds the row
    * locked from the read to the write: two patches of different members, running at once, both land, instead of the
    * second one writing back the row as it read it before the first.
    *
    * The merge is [[PatchChallengeSettingsRequest.applyTo]], run inside the transaction on the locked row. Nothing is
    * written unless it yields [[ChallengeSettingsPatchResult.Patched]]. The tenant's security profile is not written:
    * it is fixed when the tenant is created.
    */
  def patch(tenantId: TenantId, patch: PatchChallengeSettingsRequest): Task[ChallengeSettingsPatchResult]

enum ChallengeSettingsPatchResult:
  /** The tenant has no settings to merge into. */
  case NotFound

  /** The merge produced a record that cannot be stored; nothing was written. */
  case Invalid(reason: String)

  /** The record as stored. */
  case Patched(record: ChallengeSettingsRecord)

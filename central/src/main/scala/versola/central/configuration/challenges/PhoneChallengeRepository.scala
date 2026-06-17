package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.Task

trait PhoneChallengeRepository extends CacheSource[Vector[PhoneSettingsRecord]]:
  def getAll: Task[Vector[PhoneSettingsRecord]]
  def findByTenant(tenantId: TenantId): Task[Option[PhoneSettingsRecord]]
  def upsert(record: PhoneSettingsRecord): Task[Unit]

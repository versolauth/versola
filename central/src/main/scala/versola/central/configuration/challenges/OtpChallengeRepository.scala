package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.Task

trait OtpChallengeRepository extends CacheSource[Vector[OtpTemplateRecord]]:
  def getAll: Task[Vector[OtpTemplateRecord]]
  def find(id: String, tenantId: TenantId): Task[Option[OtpTemplateRecord]]
  def upsertTemplate(record: OtpTemplateRecord): Task[Unit]
  def deleteTemplate(id: String, tenantId: TenantId): Task[Unit]

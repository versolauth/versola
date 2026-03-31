package versola.central.configuration.tenants

import versola.central.configuration.edges.EdgeId
import versola.util.CacheSource
import zio.Task

trait TenantRepository extends CacheSource[Vector[TenantRecord]]:
  def getAll: Task[Vector[TenantRecord]]
  def createTenant(id: TenantId, description: String, edgeId: Option[EdgeId]): Task[Unit]
  def updateTenant(id: TenantId, description: String, edgeId: Option[EdgeId]): Task[Unit]
  def deleteTenant(id: TenantId): Task[Unit]

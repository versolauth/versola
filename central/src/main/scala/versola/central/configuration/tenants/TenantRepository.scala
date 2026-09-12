package versola.central.configuration.tenants

import versola.central.configuration.edges.EdgeId
import versola.util.CacheSource
import zio.Task

trait TenantRepository extends CacheSource[Vector[TenantRecord]]:
  def getAll: Task[Vector[TenantRecord]]

  /** Upserts the tenant row: an `id` that already exists gets its description/edgeId
    * overwritten rather than failing on the row's primary key. `TenantService.createTenant`
    * also seeds the tenant's challenge settings in a separate write after this one -- if that
    * second write fails, the tenant row this call already committed would otherwise leave a
    * retry permanently stuck on a duplicate-id conflict, unable to ever finish creating that
    * tenant. Upserting here means the retry's `createTenant` call (a no-op update, since the
    * row already has these exact values) succeeds and lets the settings write run again.
    */
  def createTenant(id: TenantId, description: String, edgeId: Option[EdgeId]): Task[Unit]
  def updateTenant(id: TenantId, description: String, edgeId: Option[EdgeId]): Task[Unit]
  def deleteTenant(id: TenantId): Task[Unit]

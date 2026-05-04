package versola.central.configuration.clients

import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.Task

trait AuthorizationPresetRepository extends CacheSource[Vector[AuthorizationPreset]]:

  def getAll: Task[Vector[AuthorizationPreset]]

  def find(id: PresetId): Task[Option[AuthorizationPreset]]

  /** Replace all presets for a client. Deletes existing presets and inserts new ones. */
  def replace(tenantId: TenantId, clientId: ClientId, presets: Seq[AuthorizationPreset]): Task[Unit]

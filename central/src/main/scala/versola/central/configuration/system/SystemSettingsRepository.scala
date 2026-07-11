package versola.central.configuration.system

import versola.util.CacheSource
import zio.Task

trait SystemSettingsRepository extends CacheSource[SystemSettingsRecord]:
  def getAll: Task[SystemSettingsRecord]
  def upsert(record: SystemSettingsRecord): Task[Unit]

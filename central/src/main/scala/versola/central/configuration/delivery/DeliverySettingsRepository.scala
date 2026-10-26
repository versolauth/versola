package versola.central.configuration.delivery

import versola.util.CacheSource
import zio.Task

/** Stores the record with its passwords as the service hands them over, already encrypted. */
trait DeliverySettingsRepository extends CacheSource[Option[DeliverySettingsRecord]]:
  /** `None` until the first save. */
  def getAll: Task[Option[DeliverySettingsRecord]]
  def upsert(record: DeliverySettingsRecord): Task[Unit]

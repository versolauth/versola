package versola.central.antifraud

import zio.Task

import java.time.Instant

/** The row of `antifraud_settings`: the settings of the deployment, which every cell copies. */
trait AntifraudSettingsRepository:
  /** The row with its version; `None` where there is none (everything off, version 0). */
  def find: Task[Option[VersionedAntifraudSettings]]

  /** Replaces the row and moves its version by one (1 for the first write).
    *
    * @param expectedVersion `Some(v)`: only when the row is at version `v` (0 meaning there is no row); `None`: whatever
    *                        is there
    * @return the new version, or `None` when the row was not at `expectedVersion` (nothing was written)
    */
  def put(settings: AntifraudSettings, actor: String, at: Instant, expectedVersion: Option[Long]): Task[Option[Long]]

package versola.antifraud

import zio.Task

import java.time.Instant

/** The row of `af_settings` as stored: the [[Tuning]] and the `version` of the row, which is the version of central's
  * settings it was copied at. */
case class StoredTuning(tuning: Tuning, version: Long)

/** This cell's copy of the settings (`af_settings`), which are central's. Every method runs under a statement timeout. */
trait AntifraudSettingsRepository:
  /** Makes the copy what central has: the row is replaced by `tuning` at `version`, central's, or removed where central
    * has none (`None`, which is everything off at version 0). The signal that makes the instances read it again
    * (`af_settings_version`) moves in the same transaction. */
  def replace(tuning: Option[Tuning], version: Long, at: Instant): Task[Unit]

  def find: Task[Option[StoredTuning]]

  /** A number that changes (0 before the first change) with every change made through this repository: what tells an
    * instance that it has to read the row again. */
  def version: Task[Long]

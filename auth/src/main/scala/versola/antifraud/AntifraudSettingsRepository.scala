package versola.antifraud

import versola.util.audit.AuditEvent
import zio.Task

import java.time.Instant

/** The row of `af_settings` as stored: the [[Tuning]] and the `version` of the row (1 for the first write, then one more for
  * every change made through the repository), which the admin API hands out as an ETag. */
case class StoredTuning(tuning: Tuning, version: Long)

/** The row of `af_settings`: the [[Tuning]] of the deployment. Every method runs under a statement timeout. */
trait AntifraudSettingsRepository:
  /** Replaces the row, in one transaction that bumps the version of the row and `af_settings_version`.
    *
    * @param expectedVersion `Some(v)`: only when the row is at version `v` (0 meaning there is no row); `None`: whatever
    *                        is there
    * @param audit `antifraud.settings_changed`, inserted in the same transaction when the row was written (the repository says
    *              which version it became)
    * @return the new version of the row, or `None` when it was not at `expectedVersion` (nothing was written)
    */
  def put(tuning: Tuning, actor: String, at: Instant, expectedVersion: Option[Long], audit: AuditEvent): Task[Option[Long]]

  /** Writes the row if there is none (a row an operator saved is left as it is) and bumps the version when it did. True when
    * it wrote. Safe to run on several instances at once. */
  def seed(tuning: Tuning, actor: String, at: Instant): Task[Boolean]

  def find: Task[Option[StoredTuning]]

  /** A number that changes (0 before the first change) with every change made through this repository: what tells an
    * instance that it has to read the row again. */
  def version: Task[Long]

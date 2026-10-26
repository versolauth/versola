package versola.antifraud

import versola.oauth.client.model.TenantId
import zio.Task

import java.time.Instant

/** The rows of `af_settings`: the [[TuningPatch]] of the deployment (`None`) and of tenants, with the history of changes.
  * A scope is a tenant or `None` for the global row. */
trait AntifraudSettingsRepository:
  /** Replaces the row, records the change and bumps the version, in one transaction. */
  def put(tenant: Option[TenantId], patch: TuningPatch, actor: String, at: Instant): Task[Unit]

  /** Removes the row and records it; false when there was none. */
  def delete(tenant: Option[TenantId], actor: String, at: Instant): Task[Boolean]

  def find(tenant: Option[TenantId]): Task[Option[TuningPatch]]

  /** A number that changes with every change made through this repository. */
  def version: Task[Long]

  def loadAll: Task[List[(Option[TenantId], TuningPatch)]]

  /** The changes, the latest first; of one scope when `scope` is given (`Some(None)` is the global one). */
  def audit(scope: Option[Option[TenantId]], limit: Int): Task[List[AntifraudTuningAudit]]

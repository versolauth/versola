package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import zio.Task

import java.time.Instant
import java.util.UUID

/** The lists (`antifraud_list_entries`), their audit and their version. */
trait AntifraudListRepository:
  /** Adds the entry, or refreshes the expiry, reason, source and shadow flag of the one that is there already (same
    * list, value type, value and tenant), and writes the audit record in the same transaction: `create` for a new
    * entry, `update` with the previous values for an existing one. Returns what is stored, which has the id of the
    * existing entry in the second case. */
  def upsert(entry: ListEntryRecord, actor: String, at: Instant): Task[ListEntryRecord]

  /** Removes the entry and records it; false when there was none. */
  def delete(id: UUID, actor: String, at: Instant): Task[Boolean]

  def find(id: UUID): Task[Option[ListEntryRecord]]

  /** The newest first. */
  def list(filter: ListEntryFilter, limit: Int, offset: Int): Task[List[ListEntryRecord]]

  /** A number that moves with every change made through this repository (`upsert`, `delete`, `purgeExpired`). */
  def version: Task[Long]

  /** Every entry that has not expired at `now`, and the version they were read at, in one snapshot: what a cell copies. */
  def active(now: Instant): Task[PolicyLists]

  /** Removes up to `limit` entries that expired at or before `now` and records each as expired. Returns how many;
    * fewer than `limit` means no more are due. Safe to run on several instances at once. */
  def purgeExpired(now: Instant, limit: Int): Task[Int]

  /** Removes up to `limit` audit records older than `before`; fewer than `limit` means no more are due. */
  def pruneAudit(before: Instant, limit: Int): Task[Int]

  /** The changes of one entry, or of all, the latest first; with `visibleTo`, only those of global entries and that
    * tenant's. */
  def audit(entryId: Option[UUID], limit: Int, visibleTo: Option[TenantId]): Task[List[AuditRecordResponse]]

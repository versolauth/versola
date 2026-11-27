package versola.central.antifraud

import versola.central.configuration.tenants.TenantId
import zio.Task

import java.time.Instant
import java.util.UUID

/** The lists (`antifraud_list_entries`), their audit, their version and the changes that made it (`antifraud_list_changes`). */
trait AntifraudListRepository:
  /** Adds the entry, or refreshes the expiry, reason, source and shadow flag of the one that is there already (same
    * list, value type, value and tenant), and writes the audit record in the same transaction: `create` for a new
    * entry, `update` with the previous values for an existing one. Returns what is stored, which has the id of the
    * existing entry in the second case. The audit record is written even when the entry is as it was, but the version
    * moves only when the entry is new or one of the expiry, reason, source and shadow flag changed. */
  def upsert(entry: ListEntryRecord, actor: String, at: Instant): Task[ListEntryRecord]

  /** Removes the entry and records it; false when there was none. */
  def delete(id: UUID, actor: String, at: Instant): Task[Boolean]

  def find(id: UUID): Task[Option[ListEntryRecord]]

  /** The newest first. */
  def list(filter: ListEntryFilter, limit: Int, offset: Int): Task[List[ListEntryRecord]]

  /** A number that moves when the set of entries a cell copies, or a field of one of them, changes: an entry created or
    * deleted, or an `upsert` that changes its expiry, reason, source or shadow flag. An `upsert` that finds the entry as
    * it is, and `purgeExpired`, leave it. An entry whose expiry passes between two reads is a change no version
    * announces, and that is fine: a cell filters what it copied by its own clock (`expires_at`), as [[active]] does by the
    * `now` it is given, so an expired entry stops matching without the lists being read again. */
  def version: Task[Long]

  /** Every entry that has not expired at `now`, and the version they were read at, in one snapshot: what a cell copies.
    * Always `full`. */
  def active(now: Instant): Task[PolicyLists]

  /** What a cell that holds the lists as of version `since` copies to catch up (versolauth/versola#1052), read in one
    * snapshot with the version:
    *
    *  - `since` equal to the version: nothing changed, an empty delta (not `full`);
    *  - `since` below it, and a change is kept for EVERY version in `(since, version]`: a delta (not `full`) -- the entries
    *    those changes name that are stored and have not expired at `now`, once each, as they are now, and the ids they
    *    name that are not (deleted, or expired), which the cell is to drop;
    *  - no `since`, `since` ahead of the version (central restored from a backup), or a version without a change (it was
    *    pruned, or the change was never written): [[active]], `full`.
    *
    * The gap is judged by counting, so a delta is never answered with a missing change in it. */
  def lists(since: Option[Long], now: Instant): Task[PolicyLists]

  /** Removes up to `limit` changes written before `before`; fewer than `limit` means no more are due. */
  def pruneChanges(before: Instant, limit: Int): Task[Int]

  /** Removes up to `limit` entries that expired at or before `now` and records each as expired. Returns how many;
    * fewer than `limit` means no more are due. Does not move the [[version]]: [[active]] leaves these entries out already.
    * Safe to run on several instances at once. */
  def purgeExpired(now: Instant, limit: Int): Task[Int]

  /** Removes up to `limit` audit records older than `before`; fewer than `limit` means no more are due. */
  def pruneAudit(before: Instant, limit: Int): Task[Int]

  /** The changes of one entry, or of all, the latest first; with `visibleTo`, only those of global entries and that
    * tenant's. */
  def audit(entryId: Option[UUID], limit: Int, visibleTo: Option[TenantId]): Task[List[AuditRecordResponse]]

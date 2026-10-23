package versola.antifraud

import zio.Task

import java.time.Instant
import java.util.UUID

trait AntifraudListRepository:
  /** Adds the entry, or refreshes the expiry, reason and source of the one that is there already (same list,
    * value type, value and tenant), and writes the audit record in the same transaction. Returns what is stored,
    * which has the id of the existing entry in the second case. */
  def upsert(entry: AntifraudListEntry, actor: String, at: Instant): Task[AntifraudListEntry]

  /** Removes the entry and records it; false when there was none. */
  def delete(id: UUID, actor: String, at: Instant): Task[Boolean]

  def find(id: UUID): Task[Option[AntifraudListEntry]]

  /** How many of the events since `since` the value would have matched, and how many there were. */
  def preview(valueType: AntifraudValueType, value: String, since: Instant): Task[ListPreview]

  def list(filter: AntifraudListFilter, limit: Int, offset: Int): Task[List[AntifraudListEntry]]

  /** A number that changes with every change made through this repository (`upsert`, `delete`, `purgeExpired`). */
  def version: Task[Long]

  /** Every entry that has not expired at `now`: what the cache is built from. */
  def loadActive(now: Instant): Task[List[ListedValue]]

  /** Adds the hits to the counters of the entries; one that is gone is skipped. */
  def addHits(hits: List[ListHit]): Task[Unit]

  /** Removes up to `limit` entries that expired at or before `now` and records each as expired. Returns how many;
    * fewer than `limit` means no more are due. Safe to run on several instances at once. */
  def purgeExpired(now: Instant, limit: Int): Task[Int]

  /** Removes up to `limit` audit records older than `before`; fewer than `limit` means no more are due. */
  def pruneAudit(before: Instant, limit: Int): Task[Int]

  /** The changes of one entry, or of all, the latest first. */
  def audit(entryId: Option[UUID], limit: Int): Task[List[AntifraudListAudit]]

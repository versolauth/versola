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

  def list(filter: AntifraudListFilter, limit: Int, offset: Int): Task[List[AntifraudListEntry]]

  /** Every entry that has not expired at `now`: what the cache is built from. */
  def loadActive(now: Instant): Task[List[ListedValue]]

  /** Adds the hits to the counters of the entries; one that is gone is skipped. */
  def addHits(hits: List[ListHit]): Task[Unit]

  /** Removes the entries that expired at or before `now` and records each as expired. Returns how many. */
  def purgeExpired(now: Instant): Task[Int]

  /** The changes of one entry, or of all, the latest first. */
  def audit(entryId: Option[UUID], limit: Int): Task[List[AntifraudListAudit]]

package versola.antifraud

import versola.oauth.client.model.TenantId
import zio.Task

import java.time.Instant
import java.util.UUID

trait AntifraudListRepository:
  /** Adds the entry, or refreshes the expiry, reason, source and shadow flag of the one that is there already (same
    * list, value type, value and tenant), and writes the audit record in the same transaction: `create` for a new
    * entry, `update` with the previous values for an existing one. Returns what is stored, which has the id of the
    * existing entry in the second case. */
  def upsert(entry: AntifraudListEntry, actor: String, at: Instant): Task[AntifraudListEntry]

  /** Removes the entry and records it; false when there was none. */
  def delete(id: UUID, actor: String, at: Instant): Task[Boolean]

  def find(id: UUID): Task[Option[AntifraudListEntry]]

  /** How many of the events since `since` the value would have matched, and how many there were, of the events of
    * `tenantId` (an entry of a tenant applies to that tenant's traffic alone) or, for `None` (a global entry), of all.
    * A phone, a user agent, an ASN or a country is counted in the event log under its timeout. A `prefix` of up to
    * `statsPrefixLength` digits is read from the rolled-up statistics (`af_dest_stats`), which hold that many digits of a
    * range per minute, and the total is their requests. A longer `prefix` is counted in the event log, which keeps more
    * digits of each number, for that prefix alone, over the same kind of events that make the statistics' requests, so
    * its share is its own and not that of the range it falls in. */
  def preview(valueType: AntifraudValueType, value: String, tenantId: Option[TenantId], since: Instant): Task[ListPreview]

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

  /** Removes up to `limit` audit records older than `before`; fewer than `limit` means no more are due. Also removes (as many,
    * not counted) the records of counter resets older than `before`, but never the newest, which the generations count from. */
  def pruneAudit(before: Instant, limit: Int): Task[Int]

  /** The changes of one entry, or of all, the latest first; with `visibleTo`, only those of global entries and that
    * tenant's. */
  def audit(entryId: Option[UUID], limit: Int, visibleTo: Option[TenantId]): Task[List[AntifraudListAudit]]

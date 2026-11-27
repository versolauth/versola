package versola.antifraud

import versola.oauth.client.model.TenantId
import zio.Task

import java.time.Instant
import java.util.UUID

/** This cell's copy of the lists (`af_list_entry`), which are central's, and what the cell counts of them itself. */
trait AntifraudListRepository:
  /** Makes the copy what central has: the entries are written (one that is there keeps its hit counters, and a row is
    * written only when a field that is copied differs), those central no longer has are removed, `version`, central's,
    * becomes the copy's, and `at` the time of the last whole copy.
    *
    * Nothing is written when the copy is at `version` already and was taken whole at or after `notBefore`, which is how
    * several instances that read the same change apply it once, and how a whole copy that is due (the last one is older
    * than `notBefore`) is taken by one of them. True when it wrote. */
  def replaceAll(entries: List[PolicyListEntry], version: Long, at: Instant, notBefore: Instant): Task[Boolean]

  /** Applies what changed in central's lists between the copy's version `from` and `to`, in one transaction under the
    * lock on the version: the entries are written (as in [[replaceAll]]), those of `removed` are removed with their hit
    * counters, and the version becomes `to`. Nothing is written when the copy is not at `from` (another instance applied
    * the change, or the copy moved), and then the answer is false. The time of the last whole copy is not touched. */
  def applyChanges(entries: List[PolicyListEntry], removed: List[UUID], from: Long, to: Long): Task[Boolean]

  /** The version of the copy and when it was last taken whole, in one read. */
  def copied: Task[AntifraudListRepository.Copy]

  /** Removes up to `limit` entries that expired before `before`: central's purge of expired entries moves no version, so it
    * does not reach the copy as a change. Fewer than `limit` means no more are due. */
  def removeExpired(before: Instant, limit: Int): Task[Int]

  /** How many of the events since `since` the value would have matched, and how many there were, of the events of
    * `tenantId` (an entry of a tenant applies to that tenant's traffic alone) or, for `None` (a global entry), of all.
    * A phone, a user agent, an ASN or a country is counted in the event log under its timeout. A `prefix` of up to
    * `statsPrefixLength` digits is read from the rolled-up statistics (`af_dest_stats`), which hold that many digits of a
    * range per minute, and the total is their requests. A longer `prefix` is counted in the event log, which keeps more
    * digits of each number, for that prefix alone, over the same kind of events that make the statistics' requests, so
    * its share is its own and not that of the range it falls in. */
  def preview(valueType: AntifraudValueType, value: String, tenantId: Option[TenantId], since: Instant): Task[ListPreview]

  /** The version of central's lists the copy was made at; 0 before the first copy. It changes with every
    * [[replaceAll]] or [[applyChanges]] that wrote, which is what tells an instance to build its snapshot again. */
  def version: Task[Long]

  /** Every entry that has not expired at `now`: what the cache is built from. */
  def loadActive(now: Instant): Task[List[ListedValue]]

  /** Adds the hits to the counters of the entries; one that is gone is skipped. */
  def addHits(hits: List[ListHit]): Task[Unit]

  /** How often each of `ids` matched in this cell; one that never did, or is not in the copy, is left out. */
  def hits(ids: List[UUID]): Task[List[ListHit]]

  /** Removes up to `limit` records of counter resets older than `before`, but never the newest, which the generations
    * count from; fewer than `limit` means no more are due. */
  def pruneCounterResets(before: Instant, limit: Int): Task[Int]

object AntifraudListRepository:
  /** @param fullAt when the copy was last taken whole; `None` before the first whole copy */
  final case class Copy(version: Long, fullAt: Option[Instant])

package versola.edge.revocation

import zio.UIO
import zio.metrics.Metric

/** Visibility into the state the revocation check depends on.
  *
  * Nothing here changes an answer — a request is served from the cache whatever these say.
  * They exist because the two ways this can go wrong are both silent: the list growing past
  * what the replica should be holding in memory, and a replica quietly failing to catch up
  * with the durable copy, which is the only way it can under-reject.
  */
object RevocationMetrics:

  private val cacheEntries = Metric.gauge("revocation_cache_entries")

  /** Seconds since this replica last agreed with the database. It rises while reloads are
    * failing, which is the window in which a missed notification would go uncorrected.
    */
  private val staleness = Metric.gauge("revocation_cache_staleness_seconds")

  private val reloadFailures = Metric.counter("revocation_cache_reload_failures_total")

  def entries(count: Int): UIO[Unit] =
    cacheEntries.set(count.toDouble)

  def reloaded(entries: Int): UIO[Unit] =
    cacheEntries.set(entries.toDouble) *> staleness.set(0)

  def reloadFailed(secondsSinceReload: Long): UIO[Unit] =
    reloadFailures.increment *> staleness.set(secondsSinceReload.toDouble)

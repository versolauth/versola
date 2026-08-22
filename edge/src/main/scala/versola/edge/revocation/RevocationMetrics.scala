package versola.edge.revocation

import zio.UIO
import zio.metrics.Metric

/** Visibility into the state the revocation check depends on.
  *
  * An incomplete cache still answers correctly \u2014 it falls back to the database on a miss \u2014
  * so the only thing that shows it happened is the cost: every unrevoked token starts paying
  * for a query. These make that visible before it turns into a latency incident, and show how
  * close the list is to outgrowing the size central holds for this edge.
  */
object RevocationMetrics:

  private val cacheComplete = Metric.gauge("revocation_cache_complete")

  private val cacheEntries = Metric.gauge("revocation_cache_entries")

  def cacheState(complete: Boolean, entries: Long): UIO[Unit] =
    cacheComplete.set(if complete then 1 else 0) *> cacheEntries.set(entries.toDouble)

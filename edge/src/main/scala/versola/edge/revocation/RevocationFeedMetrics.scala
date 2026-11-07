package versola.edge.revocation

import zio.UIO
import zio.metrics.{Metric, MetricLabel}

/** What the pull of auth's revocations reports. Nothing here changes an answer: a request is judged from the list
  * in memory whatever these say. They exist because the failure is silent -- an edge that stopped hearing from auth
  * keeps serving, and under-rejects only the revocations it has not heard about.
  */
object RevocationFeedMetrics:

  /** `ok`, `error`, or `lease_not_acquired`: a closed set, so it is a safe label. */
  enum Outcome(val label: String):
    case Ok extends Outcome("ok")
    case Error extends Outcome("error")
    case LeaseNotAcquired extends Outcome("lease_not_acquired")

  /** Seconds since a pod of this edge last read the feed to its end, by the database's clock: how far behind auth
    * this edge can be, at most, on what the feed carries. It is read from the cursor row on every poll, so every
    * pod reports the same figure, the one pod that reads and the others that learn by notification alike. It keeps
    * rising while cycles fail or nobody takes the lease, and falls to about one poll interval when they succeed.
    *
    * Not "how old the newest revocation is": that grows without bound on a deployment that is merely quiet, and
    * an alert on it would fire when nothing is wrong. A revocation reaches this edge within this value plus the
    * time one cycle takes.
    */
  private val lag = Metric.gauge("revocation_feed_lag_seconds")

  private val cycles = Metric.counter("revocation_feed_cycles_total")

  private val pages = Metric.counter("revocation_feed_pages_total")

  private val rows = Metric.counter("revocation_feed_rows_total")

  /** 1 while this pod holds the lease, 0 otherwise. Summed across pods it is 1, or 0 in the moment between cycles;
    * two for longer means two pods are reading and the lease is too short for a cycle.
    */
  private val leaseHeld = Metric.gauge("revocation_feed_lease_held")

  def cycle(outcome: Outcome): UIO[Unit] =
    cycles.tagged(MetricLabel("outcome", outcome.label)).increment

  def page(revocations: Int): UIO[Unit] =
    pages.increment *> rows.incrementBy(revocations.toLong)

  def lagging(seconds: Long): UIO[Unit] =
    lag.set(seconds.toDouble)

  /** For tests: what the gauge holds. */
  private[revocation] def currentLag: UIO[Double] =
    lag.value.map(_.value)

  def lease(held: Boolean): UIO[Unit] =
    leaseHeld.set(if held then 1.0 else 0.0)

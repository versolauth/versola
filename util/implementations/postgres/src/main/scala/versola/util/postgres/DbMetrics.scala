package versola.util.postgres

import zio.*
import zio.metrics.*

object DbMetrics:

  private[postgres] val boundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0),
    )

  /** Boundaries for the connection-acquisition wait, which spans a different range than [[boundaries]]
    * above and needs resolution at both ends of it. A pool with a free connection hands one over in
    * tens of microseconds; a saturated pool makes its callers wait up to `connection-timeout`, whose
    * configured value across this repository is 30 seconds. `boundaries`' 1 ms floor puts the entire
    * healthy case in one bucket, which is the case a p99 has to be able to distinguish from the
    * unhealthy one.
    *
    * 0.1 ms doubling to ~26 s, 19 buckets: the last finite boundary sits just past the 30 s timeout, so
    * a wait that is about to become a timeout is still a measurement rather than an overflow.
    */
  private[postgres] val connectionWaitBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.exponential(0.0001, 2.0, 19)

  private def poolLabels(poolName: String): Set[MetricLabel] =
    Set(MetricLabel("db_system", "postgresql"), MetricLabel("pool_name", poolName))

  /** Connections the pool is holding, split the way OpenTelemetry's database-pool semantics split them:
    * `used` is checked out to a caller, `idle` is available. Their sum is HikariCP's total, which is
    * deliberately not published as a third series -- a metric that is the sum of two others invites the
    * two to be added to it.
    */
  private val connectionCountGauge = Metric.gauge("db_client_connection_count")

  private val connectionMaxGauge = Metric.gauge("db_client_connection_max")

  private val connectionIdleMinGauge = Metric.gauge("db_client_connection_idle_min")

  /** Threads blocked in `getConnection` waiting for the pool to hand one back. The number that says
    * whether a pool is the constraint: busy at `maximum-pool-size` with nothing queued behind it is a
    * pool being used, and the same reading with a queue is a pool being run out of.
    */
  private val connectionPendingRequestsGauge = Metric.gauge("db_client_connection_pending_requests")

  private val connectionWaitHistogram = Metric.histogram("db_client_connection_wait_time_seconds", connectionWaitBoundaries)

  private val connectionTimeoutsTotal = Metric.counter("db_client_connection_timeouts_total")

  private val notificationsReceivedTotal =
    Metric.counter("db_notifications_received_total").tagged(MetricLabel("db_system", "postgresql"))

  private val notificationListenerReconnectsTotal =
    Metric.counter("db_notification_listener_reconnects_total").tagged(MetricLabel("db_system", "postgresql"))

  /** How many times a connection was torn down for going silent: still open, never erroring,
    * and no longer delivering. Distinct from a reconnect, which counts connections that
    * failed loudly, and the only one of the two that can point at the network path rather
    * than at the database.
    */
  private val notificationListenerSilentTotal =
    Metric.counter("db_notification_listener_silent_total").tagged(MetricLabel("db_system", "postgresql"))

  /** Whether the `LISTEN` connection is currently up, in the sense of proven to be
    * delivering rather than merely open: a connection that stops carrying notifications is
    * failed and replaced, so this reads 0 while that is happening.
    *
    * Written by the fiber that owns the connection, not by the subscriber's error path, so it
    * describes the connection rather than how promptly anyone noticed. A subscriber blocked
    * on its own reload cannot hold this at 1 over a connection that has already died.
    */
  private val notificationListenerConnectedGauge =
    Metric.gauge("db_notification_listener_connected").tagged(MetricLabel("db_system", "postgresql"))

  /** How many notifications were dropped because a subscriber fell far enough behind to fill
    * the bounded queue between it and the polling fiber. Non-zero means that subscriber is
    * now relying on its own periodic reload rather than the push path to catch up.
    */
  private val notificationListenerQueueOverflowTotal =
    Metric.counter("db_notification_listener_queue_overflow_total").tagged(MetricLabel("db_system", "postgresql"))

  def notificationReceived: UIO[Unit] =
    notificationsReceivedTotal.increment

  def notificationListenerReconnected: UIO[Unit] =
    notificationListenerReconnectsTotal.increment

  def notificationListenerWentSilent: UIO[Unit] =
    notificationListenerSilentTotal.increment

  def notificationListenerConnected(connected: Boolean): UIO[Unit] =
    notificationListenerConnectedGauge.set(if connected then 1 else 0)

  def notificationListenerQueueOverflow(dropped: Int): UIO[Unit] =
    notificationListenerQueueOverflowTotal.incrementBy(dropped.toLong)

  /** Publishes one reading of a pool's occupancy. Gauges rather than counters because every one of
    * these is a level, and the peaks the report asks for are `max_over_time` of the level -- which is
    * as close to a true peak as the publish interval, since HikariCP offers no high-water mark of its
    * own.
    */
  def poolOccupancy(poolName: String, used: Int, idle: Int, max: Int, idleMin: Int, pendingRequests: Int): UIO[Unit] =
    val labels = poolLabels(poolName)
    connectionCountGauge.tagged(labels + MetricLabel("state", "used")).set(used.toDouble) *>
      connectionCountGauge.tagged(labels + MetricLabel("state", "idle")).set(idle.toDouble) *>
      connectionMaxGauge.tagged(labels).set(max.toDouble) *>
      connectionIdleMinGauge.tagged(labels).set(idleMin.toDouble) *>
      connectionPendingRequestsGauge.tagged(labels).set(pendingRequests.toDouble)

  def connectionWait(poolName: String, seconds: Double): UIO[Unit] =
    connectionWaitHistogram.tagged(poolLabels(poolName)).update(seconds)

  /** Adds newly observed connection timeouts. Takes the increment rather than the running total for
    * the same reason [[notificationListenerQueueOverflow]] does: a counter that is `set` loses every
    * increase between two scrapes.
    */
  def connectionTimeouts(poolName: String, added: Long): UIO[Unit] =
    if added <= 0L then ZIO.unit else connectionTimeoutsTotal.tagged(poolLabels(poolName)).incrementBy(added)

  private def histogram(repository: String, operation: String, outcome: String) =
    Metric
      .histogram("db_client_operation_duration_seconds", boundaries)
      .tagged(
        MetricLabel("repository", repository),
        MetricLabel("operation", operation),
        MetricLabel("db_system", "postgresql"),
        MetricLabel("outcome", outcome),
      )

  /** Derives the simple repository class name from the call-site trace. */
  def repositoryName(trace: Trace): String =
    trace match
      case Trace(location, _, _) =>
        location.replace("$.", ".").stripSuffix("$")
      case _ => "unknown"

  /** Measures a database effect, recording its latency on both success and
    * failure paths under the `db_client_operation_duration_seconds` histogram.
    * The original exit is re-raised unchanged.
    */
  def measured[A](operation: String)(zio: Task[A])(using trace: Trace): Task[A] =
    val repository = repositoryName(trace)
    zio.exit.timed.flatMap: (elapsed, exit) =>
      val outcome = if exit.isSuccess then "success" else "failure"
      val seconds = elapsed.toNanos.toDouble / 1e9
      histogram(repository, operation, outcome).update(seconds) *> exit

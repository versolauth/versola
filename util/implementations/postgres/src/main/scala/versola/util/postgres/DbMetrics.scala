package versola.util.postgres

import zio.*
import zio.metrics.*

object DbMetrics:

  private[postgres] val boundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0),
    )

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

  def notificationListenerQueueOverflow: UIO[Unit] =
    notificationListenerQueueOverflowTotal.increment

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

package versola.util.postgres

import zio.*
import zio.metrics.*

object DbMetrics:

  private[postgres] val boundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0),
    )

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
        val lastDot = location.lastIndexOf('.')
        val owner = if lastDot >= 0 then location.substring(0, lastDot) else location
        owner.substring(owner.lastIndexOf('.') + 1).stripSuffix("$")
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

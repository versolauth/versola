package versola.cleanup

import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.util.concurrent.TimeUnit

/** Whether expired rows are being removed, and whether removal keeps up.
  *
  * Each batch already shows up in `db_client_operation_duration_seconds` (as `cleanup-batch-<table>`),
  * which says how long a batch took and whether it failed, so failures are not counted again here. It cannot say how many rows were removed,
  * whether a table is draining as fast as rows expire, or when a table was last cleaned -- which is
  * what tells a table that is keeping up from one that has quietly stopped being cleaned.
  *
  * Labelled by table name, which is configuration, so cardinality is bounded.
  */
object CleanupMetrics:

  private val rowsDeleted = Metric.counter("cleanup_rows_deleted_total")
  private val fullBatches = Metric.counter("cleanup_full_batches_total")
  private val lastSuccess = Metric.gauge("cleanup_last_success_timestamp_seconds")

  private def table(name: String): Set[MetricLabel] = Set(MetricLabel("table", name))

  /** A batch that finished. `full` means it deleted as many rows as it was allowed to, so more
    * expired rows were probably waiting: a table whose batches are routinely full is expiring
    * rows faster than it is cleaned, until a batch comes back short.
    */
  def batchSucceeded(tableName: String, deleted: Int, full: Boolean): UIO[Unit] =
    for
      now <- Clock.currentTime(TimeUnit.SECONDS)
      _ <- rowsDeleted.tagged(table(tableName)).incrementBy(deleted.toLong)
      _ <- fullBatches.tagged(table(tableName)).increment.when(full)
      _ <- lastSuccess.tagged(table(tableName)).set(now.toDouble)
    yield ()

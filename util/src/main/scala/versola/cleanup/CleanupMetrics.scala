package versola.cleanup

import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.util.concurrent.TimeUnit

/** Whether expired rows are being removed, whether removal keeps up, and how big the tables are.
  *
  * Each batch already shows up in `db_client_operation_duration_seconds` (as `cleanup-batch-<table>`),
  * which says how long a batch took and whether it failed, so failures are not counted again here. It
  * cannot say how many rows were removed, how many expired rows are still waiting, or when a table was
  * last cleaned -- which is what tells a table that is keeping up from one that has quietly stopped
  * being cleaned.
  *
  * Labelled by table name, which is configuration, so cardinality is bounded.
  */
object CleanupMetrics:

  private val rowsDeleted = Metric.counter("cleanup_rows_deleted_total")
  private val lastSuccess = Metric.gauge("cleanup_last_success_timestamp_seconds")
  private val expiredRows = Metric.gauge("cleanup_expired_rows")
  private val oldestExpiredAge = Metric.gauge("cleanup_oldest_expired_age_seconds")
  private val tableRows = Metric.gauge("db_table_rows_estimate")
  private val configured = Metric.gauge("cleanup_configured")

  private def table(name: String): Set[MetricLabel] = Set(MetricLabel("table", name))

  def batchSucceeded(tableName: String, deleted: Int): UIO[Unit] =
    for
      now <- Clock.currentTime(TimeUnit.SECONDS)
      _ <- rowsDeleted.tagged(table(tableName)).incrementBy(deleted.toLong)
      _ <- lastSuccess.tagged(table(tableName)).set(now.toDouble)
    yield ()

  /** Rows past their `expires_at` that are still in the table, counted up to a cap (so a value equal
    * to the cap means "at least that many"), and how long ago the oldest of them expired. Both fall
    * towards zero while cleanup keeps up; a rising count or age is a table cleanup is losing to.
    */
  def expired(tableName: String, rows: Long, oldestAgeSeconds: Double): UIO[Unit] =
    expiredRows.tagged(table(tableName)).set(rows.toDouble) *>
      oldestExpiredAge.tagged(table(tableName)).set(oldestAgeSeconds)

  /** The planner's row estimate, not a count: free to read and right to within the autovacuum's
    * lag, which is what "how many sessions, tokens, users are there" needs.
    */
  def estimatedRows(tableName: String, rows: Long): UIO[Unit] =
    tableRows.tagged(table(tableName)).set(rows.toDouble)

  /** 1 when cleanup is configured for the table, 0 when it has an `expires_at` and nothing removes what
    * expires. A 0 next to a growing `cleanup_expired_rows` is a table that will only ever grow.
    */
  def isConfigured(tableName: String, isCleaned: Boolean): UIO[Unit] =
    configured.tagged(table(tableName)).set(if isCleaned then 1.0 else 0.0)

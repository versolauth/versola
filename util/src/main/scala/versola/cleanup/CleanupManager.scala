package versola.cleanup

import zio.*

import java.util.concurrent.TimeUnit

/** Service for managing periodic cleanup of expired database records.
  *
  * The cleanup manager runs periodic jobs that delete expired rows from configured tables. Implementations should use
  * database-specific patterns to safely handle multiple concurrent instances (e.g., SELECT FOR UPDATE SKIP LOCKED for
  * PostgreSQL).
  */
trait CleanupManager:
  /** Start all cleanup jobs.
    *
    * This method starts background fibers for each configured table. Each fiber runs periodically according to the
    * table's configured interval.
    *
    * @return
    *   A Task that completes when all cleanup jobs have been started
    */
  def start(): RIO[Scope, Unit]

  /** Stop all cleanup jobs gracefully.
    *
    * This method interrupts all running cleanup fibers. Any cleanup batch currently in progress will be allowed to
    * complete before the fiber terminates.
    *
    * @return
    *   A Task that completes when all cleanup jobs have been stopped
    */
  def stop(): UIO[Unit]

object CleanupManager:
  /** @param rows
    *   Expired rows still in the table, counted up to the configured cap.
    * @param oldestAgeSeconds
    *   How long ago the oldest of them expired; 0 when none has.
    */
  /** @param indexed
    *   Whether an index leads with `expires_at`, so that counting the expired rows is a range scan.
    */
  case class ExpiryTable(name: String, indexed: Boolean)

  /** @param rows
    *   The database's row estimate; `None` on a table that has never been analysed (not the same as empty).
    * @param bytes
    *   Table plus indexes plus out-of-line storage.
    */
  case class TableSize(rows: Option[Long], bytes: Long)

  case class ExpiredStats(rows: Long, oldestAgeSeconds: Double)

  /** Tables that never expire, so are not cleaned, whose size the dashboards show anyway. Measured wherever
    * the table exists; a service whose database lacks it just has no series.
    */
  val EstimatedWithoutCleanup: List[String] = List("users")

  /** Abstract base implementation of CleanupManager with generic logic.
    *
    * Subclasses only need to implement the database-specific cleanup batch operation.
    *
    * @param config
    *   Cleanup configuration
    */
  abstract class Base(
      config: CleanupConfig,
      fibers: Ref[List[Fiber.Runtime[Throwable, Long]]],
  ) extends CleanupManager:

    /** Database-specific implementation of batch cleanup.
      *
      * This method should delete expired rows from the specified table using database-specific patterns (e.g., SELECT
      * FOR UPDATE SKIP LOCKED for PostgreSQL).
      *
      * @param tableName
      *   The name of the table to clean up
      * @param batchSize
      *   Maximum number of rows to delete in this batch
      * @param keyColumn
      *   The column used to identify rows for deletion
      * @return
      *   Number of rows deleted
      */
    protected def cleanupBatch(tableName: String, batchSize: Int, keyColumn: String): Task[Int]

    /** Every table in this service's database that has an `expires_at` column, whether or not cleanup is
      * configured for it. Empty when the implementation cannot tell.
      */
    protected def tablesWithExpiry: Task[List[CleanupManager.ExpiryTable]] = ZIO.succeed(Nil)

    /** Expired rows still in the table (counted up to `cap`) and the age of the oldest one, or `None` when
      * the implementation cannot tell. Read-only.
      */
    protected def expiredStats(tableName: String, cap: Int): Task[Option[CleanupManager.ExpiredStats]] = ZIO.none

    /** How big the table is, or `None` when there is no such table here. Read-only and cheap, unlike a count. */
    protected def tableSize(tableName: String): Task[Option[CleanupManager.TableSize]] = ZIO.none

    override def start(): RIO[Scope, Unit] =
      Semaphore.make(config.maxThreads).flatMap { semaphore =>
        ZIO.logInfo(
          s"Starting cleanup manager with max-threads=${config.maxThreads}, tables=${config.tables.size}",
        ) *>
          ZIO.foreachPar(config.tables) { tableConfig =>
            // A failed run is logged and the table is tried again on the next interval. Without the catch the
            // schedule ends on the first failure, and nothing starts the job again: one dropped connection
            // would stop that table being cleaned until the service restarted, with no error beyond the one line.
            drainTable(semaphore, tableConfig)
              .catchAllCause(cause =>
                ZIO.logErrorCause(
                  s"Cleanup of ${tableConfig.tableName} failed; retrying in ${tableConfig.interval}",
                  cause,
                ),
              )
              .repeat(Schedule.spaced(tableConfig.interval))
              .forkScoped
          }.zipWith(reportState.repeat(Schedule.spaced(config.statsInterval)).forkScoped)(_ :+ _)
      }.flatMap(fib => fibers.set(fib))

    override def stop(): UIO[Unit] =
      for
        _ <- ZIO.logInfo("Stopping cleanup manager...")
        fib <- fibers.get
        _ <- ZIO.foreachParDiscard(fib)(fiber => fiber.interrupt *> fiber.join.ignore)
        _ <- ZIO.logInfo(s"Stopped ${fib.size} cleanup jobs")
      yield ()

    /** Measures what cleanup has left: expired rows and the oldest one for every cleaned table, and a row
      * estimate for those plus the tables with no expiry whose size is worth seeing. One table failing to
      * answer (no such table in this service's database, a timeout) is logged and does not stop the rest.
      */
    private def reportState: Task[Unit] =
      def tolerate(table: String)(measure: Task[Unit]): UIO[Unit] =
        measure.catchAllCause(cause => ZIO.logWarningCause(s"Could not measure $table", cause))
      val cleaned = config.tables.map(_.tableName).toSet
      for
        found <- tablesWithExpiry.catchAllCause(cause =>
          ZIO.logWarningCause("Could not list the tables with an expires_at", cause).as(Nil),
        )
        // what is configured is always measured, even if discovery found nothing or missed it
        expiring = found ++ config.tables.map(_.tableName).filterNot(found.map(_.name).toSet).map(CleanupManager.ExpiryTable(_, indexed = true))
        _ <- ZIO.foreachDiscard(expiring): table =>
          tolerate(table.name):
            CleanupMetrics.isConfigured(table.name, cleaned(table.name)) *>
              // a count over a table with no index on expires_at would read the whole table every time
              expiredStats(table.name, config.expiredCountCap)
                .flatMap(ZIO.foreachDiscard(_)(stats => CleanupMetrics.expired(table.name, stats.rows, stats.oldestAgeSeconds)))
                .when(table.indexed)
                .unit
        _ <- ZIO.foreachDiscard((expiring.map(_.name) ++ CleanupManager.EstimatedWithoutCleanup).distinct): table =>
          tolerate(table):
            tableSize(table).flatMap(ZIO.foreachDiscard(_)(size => CleanupMetrics.size(table, size.rows, size.bytes)))
      yield ()

    private def drainTable(semaphore: Semaphore, config: TableCleanupConfig): Task[Unit] =
      val keyColumn = config.keyColumn.getOrElse("id")
      def runBatch: Task[Int] =
        semaphore.withPermit:
          for
            start <- Clock.currentTime(TimeUnit.MILLISECONDS)
            deleted <- cleanupBatch(config.tableName, config.batchSize, keyColumn)
            _ <- CleanupMetrics.batchSucceeded(config.tableName, deleted)
            end <- Clock.currentTime(TimeUnit.MILLISECONDS)
            _ <- ZIO.logInfo(s"Cleaned ${config.tableName}: $deleted rows in ${end - start}ms")
          yield deleted
      def loop: Task[Unit] = runBatch.flatMap(deleted => ZIO.when(deleted >= config.batchSize)(loop).unit)
      loop

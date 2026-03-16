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
      * @return
      *   Number of rows deleted
      */
    protected def cleanupBatch(tableName: String, batchSize: Int): Task[Int]

    override def start(): RIO[Scope, Unit] =
      Semaphore.make(config.maxThreads).flatMap { semaphore =>
        ZIO.logInfo(
          s"Starting cleanup manager with max-threads=${config.maxThreads}, tables=${config.tables.size}",
        ) *>
          ZIO.foreachPar(config.tables) { tableConfig =>
            semaphore.withPermit(cleanupTable(tableConfig))
              .repeat(Schedule.spaced(tableConfig.interval))
              .forkScoped
          }
      }.flatMap(fib => fibers.set(fib))

    override def stop(): UIO[Unit] =
      for
        _ <- ZIO.logInfo("Stopping cleanup manager...")
        fib <- fibers.get
        _ <- ZIO.foreachParDiscard(fib)(fiber => fiber.interrupt *> fiber.join.ignore)
        _ <- ZIO.logInfo(s"Stopped ${fib.size} cleanup jobs")
      yield ()

    private def cleanupTable(config: TableCleanupConfig): Task[CleanupResult] =
      for
        start <- Clock.currentTime(TimeUnit.MILLISECONDS)
        deleted <- cleanupBatch(config.tableName, config.batchSize)
        end <- Clock.currentTime(TimeUnit.MILLISECONDS)
        duration = end - start
        _ <- ZIO.logInfo(s"Cleaned ${config.tableName}: $deleted rows in ${duration}ms")
      yield CleanupResult(
        tableName = config.tableName,
        rowsDeleted = deleted,
        durationMs = duration,
      )

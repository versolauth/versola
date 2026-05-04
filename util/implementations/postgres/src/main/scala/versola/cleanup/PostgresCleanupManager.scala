package versola.cleanup

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.*
import zio.config.magnolia.deriveConfig

/** PostgreSQL implementation of CleanupManager using SELECT FOR UPDATE SKIP LOCKED pattern.
  *
  * This implementation:
  *   - Uses SKIP LOCKED to safely handle multiple instances cleaning the same database
  *   - Deletes expired rows in configurable batch sizes
  *   - All tables are expected to have an `expires_at` column
  *
  * @param xa
  *   Database transactor for executing queries
  * @param config
  *   Cleanup configuration
  */
class PostgresCleanupManager(
    xa: TransactorZIO,
    config: CleanupConfig,
    fibers: Ref[List[Fiber.Runtime[Throwable, Long]]],
) extends CleanupManager.Base(config, fibers):

  override protected def cleanupBatch(tableName: String, batchSize: Int): Task[Int] =
    val table = SqlLiteral(tableName)
    xa.connect {
      sql"""
        DELETE FROM $table
        WHERE id IN (
          SELECT id FROM $table
          WHERE expires_at < NOW()
          ORDER BY expires_at
          LIMIT $batchSize
          FOR UPDATE SKIP LOCKED
        )
      """.update.run()
    }

object PostgresCleanupManager:
  /** ZIO Layer that creates, starts, and properly releases the CleanupManager.
    *
    * The manager is started automatically when the layer is acquired and stopped when the scope is closed.
    */
  val live: ZLayer[TransactorZIO & ConfigProvider & Scope, Throwable, CleanupManager] =
    cleanupConfig >>> ZLayer:
      ZIO.acquireRelease(
        acquire = for
          xa <- ZIO.service[TransactorZIO]
          config <- ZIO.service[CleanupConfig]
          fibers <- Ref.make(List.empty[Fiber.Runtime[Throwable, Long]])
          cleanupManager = PostgresCleanupManager(xa, config, fibers)
          _ <- cleanupManager.start()
        yield cleanupManager
      )(_.stop())

  private def cleanupConfig: ZLayer[ConfigProvider, Config.Error, CleanupConfig] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[ConfigProvider](_
        .load(Config.Nested("cleanup", deriveConfig[CleanupConfig]))
      )
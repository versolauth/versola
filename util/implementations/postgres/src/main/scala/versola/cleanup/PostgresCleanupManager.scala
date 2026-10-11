package versola.cleanup

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
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
) extends CleanupManager.Base(config, fibers), BasicCodecs:

  override protected def cleanupBatch(tableName: String, batchSize: Int, keyColumn: String): Task[Int] =
    val table = SqlLiteral(tableName)
    val key = SqlLiteral(keyColumn)
    val tableKey = SqlLiteral(s"$tableName.$keyColumn")
    val subqKey = SqlLiteral(s"subq.$keyColumn")
    xa.connectMeasured(s"cleanup-batch-$tableName") {
      sql"""
        DELETE FROM $table
        USING (
          SELECT $key FROM $table
          WHERE expires_at < NOW()
          ORDER BY expires_at
          LIMIT $batchSize
          FOR UPDATE SKIP LOCKED
        ) subq
        WHERE $tableKey = $subqKey
      """.update.run()
    }

  override protected def tablesWithExpiry: Task[List[CleanupManager.ExpiryTable]] =
    xa.connectMeasured("cleanup-list-expiring-tables") {
      sql"""
        SELECT c.relname,
               EXISTS (
                 SELECT 1 FROM pg_index i
                 JOIN pg_attribute ia ON ia.attrelid = i.indrelid AND ia.attnum = i.indkey[0]
                 WHERE i.indrelid = c.oid AND ia.attname = 'expires_at'
               )
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'expires_at' AND NOT a.attisdropped
        WHERE c.relkind IN ('r', 'p') AND n.nspname = current_schema()
        ORDER BY c.relname
      """.query[(String, Boolean)].run().toList
    }.map(_.map((name, indexed) => CleanupManager.ExpiryTable(name, indexed)))

  override protected def expiredStats(tableName: String, cap: Int): Task[Option[CleanupManager.ExpiredStats]] =
    val table = SqlLiteral(tableName)
    // `expires_at` is indexed on every table cleaned, so both reads are index range scans; the cap bounds the
    // count however far behind the table is, and MIN() is the first entry of the range.
    xa.connectMeasured(s"cleanup-stats-$tableName") {
      sql"""
        SELECT
          (SELECT COUNT(*) FROM (SELECT 1 FROM $table WHERE expires_at < NOW() LIMIT $cap) expired),
          (SELECT EXTRACT(EPOCH FROM NOW() - MIN(expires_at))::float8 FROM $table WHERE expires_at < NOW())
      """.query[(Long, Option[Double])].run().headOption
    }.map(_.map((rows, age) => CleanupManager.ExpiredStats(rows, age.getOrElse(0.0))))

  override protected def tableSize(tableName: String): Task[Option[CleanupManager.TableSize]] =
    // reltuples is -1 on a table that has never been vacuumed or analysed: no estimate yet, not "empty"
    xa.connectMeasured(s"cleanup-size-$tableName") {
      sql"""
        SELECT reltuples::bigint, pg_total_relation_size(oid)
        FROM pg_class WHERE oid = to_regclass($tableName)
      """.query[(Long, Long)].run().headOption
    }.map(_.map((rows, bytes) => CleanupManager.TableSize(Some(rows).filter(_ >= 0), bytes)))

object PostgresCleanupManager:
  /** ZIO Layer that creates, starts, and properly releases the CleanupManager.
    *
    * The manager is started automatically when the layer is acquired and stopped when the scope is closed.
    */
  val live: ZLayer[TransactorZIO & ConfigProvider & Scope, Throwable, CleanupManager] =
    cleanupConfig >>> ZLayer:
      ZIO.acquireRelease(
        acquire =
          for
            xa <- ZIO.service[TransactorZIO]
            config <- ZIO.service[CleanupConfig]
            fibers <- Ref.make(List.empty[Fiber.Runtime[Throwable, Long]])
            cleanupManager = PostgresCleanupManager(xa, config, fibers)
          yield cleanupManager,
      )(_.stop())
        // Outside `acquire` on purpose: acquire runs uninterruptibly, and fibers forked there
        // inherit that, so `stop()`'s `fiber.interrupt` would wait for them forever (every
        // shutdown then ran into the 15 s `gracefulShutdownTimeout`).
        .tap(_.start())

  private def cleanupConfig: ZLayer[ConfigProvider, Config.Error, CleanupConfig] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[ConfigProvider](_
        .load(Config.Nested("cleanup", deriveConfig[CleanupConfig])))

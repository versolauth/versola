package versola.cleanup

import zio.Duration

/** Configuration for the cleanup manager.
 *
 * @param maxThreads
 *   Maximum number of concurrent cleanup operations (recommended: 1-2)
 * @param tables
 *   List of table cleanup configurations
 */
case class CleanupConfig(
    maxThreads: Int,
    tables: List[TableCleanupConfig],
)

/** Configuration for a single table cleanup job.
 *
 * All tables are expected to have an `expires_at` column for expiration tracking.
 *
 * @param tableName
 *   The name of the table to clean up
 * @param batchSize
 *   Number of rows to delete in a single batch
 * @param interval
 *   How often to run cleanup for this table
 */
case class TableCleanupConfig(
    tableName: String,
    batchSize: Int,
    interval: Duration,
)

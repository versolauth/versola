package versola.cleanup

import zio.*

/** Configuration for the cleanup manager.
 *
 * @param maxThreads
 *   Maximum number of concurrent cleanup operations (recommended: 1-2)
 * @param tables
 *   List of table cleanup configurations
 * @param statsInterval
 *   How often to measure what is left to clean (expired rows, oldest expired row) and how big the
 *   tables are. Defaults to one minute.
 * @param expiredCountCap
 *   Expired rows are counted up to this many, so measuring a table that is badly behind stays cheap.
 *   Defaults to 100000.
 */
case class CleanupConfig(
    maxThreads: Int,
    tables: List[TableCleanupConfig],
    statsInterval: Duration = 1.minute,
    expiredCountCap: Int = 100_000,
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
 * @param keyColumn
 *   The column used to identify rows for deletion. Defaults to `id` if not specified.
 *   Use `ctid` for tables with composite primary keys.
 */
case class TableCleanupConfig(
    tableName: String,
    batchSize: Int,
    interval: Duration,
    keyColumn: Option[String] = None,
)

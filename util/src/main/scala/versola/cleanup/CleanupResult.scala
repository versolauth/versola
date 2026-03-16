package versola.cleanup

/** Result of a cleanup operation.
 *
 * @param tableName
 *   The name of the table that was cleaned
 * @param rowsDeleted
 *   Number of rows deleted in this cleanup batch
 * @param durationMs
 *   Time taken to complete the cleanup operation in milliseconds
 */
case class CleanupResult(
    tableName: String,
    rowsDeleted: Int,
    durationMs: Long,
)

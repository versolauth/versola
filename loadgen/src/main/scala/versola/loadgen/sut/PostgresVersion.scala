package versola.loadgen.sut

/** Which `pg_stat_*` views and columns the database being captured actually has, read from its
  * own `server_version_num` rather than assumed.
  *
  * The statistics 07-wal-tuning.md asks for moved twice in three releases: checkpoint counters
  * left `pg_stat_bgwriter` for `pg_stat_checkpointer` in 17, and 18 both moved the WAL I/O
  * timings out of `pg_stat_wal` into `pg_stat_io` and replaced that view's `op_bytes` with
  * `read_bytes`/`write_bytes`. A `SELECT` written for one major is not a `SELECT` that returns
  * less on another -- it fails on the missing column and takes the whole snapshot with it. Each
  * predicate below is therefore the version the *column list* is chosen by, and a section the
  * server cannot answer for is `None` in [[SutCounters]] rather than a zero.
  *
  * A major newer than any of these is read with the newest shape known here, which is the only
  * choice that does not require a release of this binary per release of Postgres; if that shape
  * has moved again, the capture fails and is logged, and the campaign runs on without a database
  * section rather than without a campaign.
  */
case class PostgresVersion(serverVersionNum: Int):

  def major: Int = serverVersionNum / 10000

  /** `pg_stat_wal` exists from Postgres 14, and `wal_buffers_full` with it. */
  def hasStatWal: Boolean = major >= 14

  /** `pg_stat_io` exists from Postgres 16; rows with `object = 'wal'` with it. */
  def hasStatIo: Boolean = major >= 16

  /** `read_bytes`/`write_bytes` replaced `op_bytes` in Postgres 18. */
  def hasStatIoBytes: Boolean = major >= 18

  /** `pg_stat_checkpointer` exists from Postgres 17; before it the same counters are columns of
    * `pg_stat_bgwriter`, which 17 removed them from.
    */
  def hasStatCheckpointer: Boolean = major >= 17

  /** `num_done` and `slru_written` were added to `pg_stat_checkpointer` in Postgres 18. */
  def hasCheckpointerTotals: Boolean = major >= 18

  /** `total_autovacuum_time` was added to `pg_stat_all_tables` in Postgres 18. */
  def hasVacuumTimes: Boolean = major >= 18

object PostgresVersion:

  /** The oldest major this reader can produce a WAL section for. Below it a capture still
    * records table churn, database activity and size -- everything §3 asks for that does not
    * live in `pg_stat_wal` -- so it degrades rather than refuses.
    */
  val oldestWithWalStats: Int = 14

package versola.loadgen.sut

import zio.{Task, ZIO}

import java.sql.{Connection, ResultSet, SQLException, Statement}

/** One reading of one PgBouncer admin console, plus the version string that produced it. */
case class PoolerStatsReading(version: String, stats: PoolerStats)

/** Reads `SHOW STATS`, `SHOW POOLS` and `SHOW CONFIG` off a PgBouncer admin console, from a plain
  * JDBC connection to its virtual `pgbouncer` database.
  *
  * Two things about that console shape every decision here, and neither applies to
  * [[SutStatsReader]] against a real server.
  *
  * **It speaks only the simple query protocol.** pgjdbc uses the extended protocol for everything
  * by default, and PgBouncer answers a `Parse` message with "unsupported pkt type" -- so the
  * connection must be opened with `preferQueryMode=simple` ([[PoolerStatsCapture]] sets it) and
  * every statement here goes through [[Statement]] rather than `prepareStatement`. There are no
  * parameters to bind in any case: a `SHOW` command takes none.
  *
  * **It cannot be opened without also disarming pgjdbc's connect-time `SET`.** See
  * [[PoolerStatsCapture]] and its `replication=database` for why: every released PgBouncer
  * rejects that statement and drops the connection before this reader ever runs a `SHOW`.
  *
  * **A `SHOW` command takes no column list.** [[SutStatsReader]] writes the columns it wants and
  * argues that picking them out of a result instead would turn a rename into a silent `None`.
  * That argument does not transfer, because here there is no column list to write: PgBouncer
  * chooses the columns and a build that does not track prepared statements simply does not report
  * `total_client_parse_count`. So the columns present are read from the result set's own
  * metadata, and an absent one is the honest `None` that a build without the counter means --
  * rather than a version predicate over a version string that only `SHOW VERSION` reports, in
  * prose, and that says nothing about which distribution patched which column in.
  */
object PoolerStatsReader:

  /** Bounds each `SHOW` for [[SutStatsReader.queryTimeoutSeconds]]'s reason. pgjdbc implements
    * this by racing a cancel request, which the admin console does not honour, so the socket
    * timeout is the one that actually fires here -- this is the cheap half of the pair and is set
    * anyway, because a future build that does honour it should not need this file changed.
    */
  val queryTimeoutSeconds: Int = 15

  def read(connection: Connection): Task[PoolerStatsReading] =
    ZIO.attemptBlocking:
      PoolerStatsReading(
        version = version(connection),
        stats = PoolerStats(
          counters = PoolerCounters(databases = databases(connection)),
          gauges = PoolerGauges(
            pools = pools(connection),
            maxClientConnections = setting(connection, "max_client_conn").flatMap(_.toIntOption),
            defaultPoolSize = setting(connection, "default_pool_size").flatMap(_.toIntOption),
          ),
        ),
      )

  /** `SHOW VERSION` answers with one unnamed-in-practice column carrying a prose string --
    * "PgBouncer 1.21.0". Recorded verbatim rather than parsed into numbers: nothing here branches
    * on it, and the reading it labels is what a later reader needs it for.
    */
  private def version(connection: Connection): String =
    all(connection, "SHOW VERSION")(row => Option(row.getString(1)).getOrElse("")).headOption
      .getOrElse(throw IllegalStateException("the PgBouncer admin console did not answer SHOW VERSION"))

  /** Every database the pooler fronts, including its own `pgbouncer` virtual one. That row is
    * kept rather than filtered: the captures themselves are the only traffic on it, so it is the
    * reading that shows what this instrument cost the thing it measured.
    */
  private def databases(connection: Connection): List[PoolerDatabaseStats] =
    all(connection, "SHOW STATS"): row =>
      PoolerDatabaseStats(
        database = string(row, "database"),
        xactCount = long(row, "total_xact_count").getOrElse(0L),
        queryCount = long(row, "total_query_count").getOrElse(0L),
        serverAssignmentCount = long(row, "total_server_assignment_count"),
        receivedBytes = long(row, "total_received").getOrElse(0L),
        sentBytes = long(row, "total_sent").getOrElse(0L),
        xactTimeMicros = long(row, "total_xact_time").getOrElse(0L),
        queryTimeMicros = long(row, "total_query_time").getOrElse(0L),
        waitTimeMicros = long(row, "total_wait_time").getOrElse(0L),
        clientParseCount = long(row, "total_client_parse_count"),
        serverParseCount = long(row, "total_server_parse_count"),
        bindCount = long(row, "total_bind_count"),
        clientLoginCount = long(row, "total_client_login_count"),
      )

  /** `maxwait` and `maxwait_us` are seconds and the microsecond remainder of one wait, not two
    * waits, so they are combined here -- a report that showed them apart would invite reading
    * `maxwait_us` as the whole figure whenever the wait is under a second, which is most of them.
    */
  private def pools(connection: Connection): List[PoolerPoolStats] =
    all(connection, "SHOW POOLS"): row =>
      PoolerPoolStats(
        database = string(row, "database"),
        user = string(row, "user"),
        clientsActive = long(row, "cl_active").getOrElse(0L),
        clientsWaiting = long(row, "cl_waiting").getOrElse(0L),
        serversActive = long(row, "sv_active").getOrElse(0L),
        serversIdle = long(row, "sv_idle").getOrElse(0L),
        serversUsed = long(row, "sv_used").getOrElse(0L),
        serversTested = long(row, "sv_tested").getOrElse(0L),
        serversLogin = long(row, "sv_login").getOrElse(0L),
        maxWaitMicros = long(row, "maxwait").getOrElse(0L) * 1000000L + long(row, "maxwait_us").getOrElse(0L),
        poolMode = string(row, "pool_mode"),
      )

  /** One row of `SHOW CONFIG`, by key.
    *
    * `SHOW CONFIG` is the one command here an admin console may refuse outright -- it is
    * restricted to `admin_users` on builds that separate the two roles, and the coordinator is
    * better deployed as a `stats_users` login. A refusal costs the two denominators in
    * [[PoolerGauges]] and nothing else, which is why it is caught here rather than in
    * [[PoolerStatsCapture]], where it would cost the whole reading.
    */
  private def setting(connection: Connection, key: String): Option[String] =
    try all(connection, "SHOW CONFIG")(row => (string(row, "key"), string(row, "value"))).collectFirst:
        case (name, value) if name == key => value
    catch case _: SQLException => None

  private def all[A](connection: Connection, show: String)(read: ResultSet => A): List[A] =
    val statement = connection.createStatement()
    try
      statement.setQueryTimeout(queryTimeoutSeconds)
      val rows = statement.executeQuery(show)
      try
        val builder = List.newBuilder[A]
        while rows.next() do builder += read(rows)
        builder.result()
      finally rows.close()
    finally statement.close()

  /** Everything the admin console sends is a text field, including the counters: it is not a
    * Postgres server and its result sets carry no typed columns. So a counter is parsed from its
    * string rather than taken with `getLong`, and a column this build does not report is `None`
    * -- told apart from one reporting zero, which the `getOrElse(0L)` above would otherwise
    * conflate for the counters every build does have.
    */
  private def long(row: ResultSet, column: String): Option[Long] =
    value(row, column).flatMap(_.trim.toLongOption)

  private def string(row: ResultSet, column: String): String = value(row, column).getOrElse("")

  private def value(row: ResultSet, column: String): Option[String] =
    try Option(row.getString(column))
    catch case _: SQLException => None

package versola.cleanup

import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

object CleanupManagerSpec extends ZIOSpecDefault:

  private case class Call(tableName: String, batchSize: Int, keyColumn: String)

  /** Test double for [[CleanupManager.Base]]: records every batch it is asked to run and
    * replays a scripted list of deletion counts, falling back to 0 (a drained table) once
    * the script runs out.
    */
  private class Recording(
      config: CleanupConfig,
      fibers: Ref[List[Fiber.Runtime[Throwable, Long]]],
      calls: Ref[Chunk[Call]],
      counts: Ref[List[Int]],
  ) extends CleanupManager.Base(config, fibers):
    override protected def cleanupBatch(tableName: String, batchSize: Int, keyColumn: String): Task[Int] =
      calls.update(_ :+ Call(tableName, batchSize, keyColumn)) *>
        counts.modify {
          case head :: tail => (head, tail)
          case Nil => (0, Nil)
        }

  private def manager(config: CleanupConfig, counts: List[Int] = Nil) =
    for
      fibers <- Ref.make(List.empty[Fiber.Runtime[Throwable, Long]])
      calls <- Ref.make(Chunk.empty[Call])
      scripted <- Ref.make(counts)
    yield (Recording(config, fibers, calls, scripted), calls, fibers)

  private def tableConfig(
      tableName: String = "sessions",
      batchSize: Int = 10,
      interval: Duration = 1.hour,
      keyColumn: Option[String] = None,
  ) = TableCleanupConfig(tableName, batchSize, interval, keyColumn)

  private def counter(name: String, table: String): UIO[Double] =
    Metric.counter(name).tagged(MetricLabel("table", table)).value.map(_.count)

  private def gauge(name: String, table: String): UIO[Double] =
    Metric.gauge(name).tagged(MetricLabel("table", table)).value.map(_.value)

  /** Fails its first batch, then answers like [[Recording]]. */
  private class Flaky(config: CleanupConfig, fibers: Ref[List[Fiber.Runtime[Throwable, Long]]], calls: Ref[Int])
    extends CleanupManager.Base(config, fibers):
    override protected def cleanupBatch(tableName: String, batchSize: Int, keyColumn: String): Task[Int] =
      calls.updateAndGet(_ + 1).flatMap(n => if n == 1 then ZIO.fail(RuntimeException("connection reset")) else ZIO.succeed(0))

  /** Answers what the database would say about three tables with an `expires_at`: one cleaned, one not, and
    * one with no index on it (which must never be counted). */
  private class Reporting(config: CleanupConfig, fibers: Ref[List[Fiber.Runtime[Throwable, Long]]], counted: Ref[List[String]])
    extends CleanupManager.Base(config, fibers):
    override protected def cleanupBatch(tableName: String, batchSize: Int, keyColumn: String): Task[Int] = ZIO.succeed(0)
    override protected def tablesWithExpiry =
      ZIO.succeed(List(
        CleanupManager.ExpiryTable("rep_cleaned", indexed = true),
        CleanupManager.ExpiryTable("rep_forgotten", indexed = true),
        CleanupManager.ExpiryTable("rep_unindexed", indexed = false),
      ))
    override protected def expiredStats(tableName: String, cap: Int) =
      counted.update(tableName :: _).as(Some(CleanupManager.ExpiredStats(
        rows = if tableName == "rep_cleaned" then 7 else 900,
        oldestAgeSeconds = 42.0,
      )))
    override protected def estimatedRows(tableName: String) =
      ZIO.succeed(if tableName == "users" then None else Some(1000L))

  def spec = suite("CleanupManager.Base")(
    test("keeps cleaning a table after a batch fails, on the next interval") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig("flaky_rows", interval = 1.hour)))
      for
        fibers <- Ref.make(List.empty[Fiber.Runtime[Throwable, Long]])
        calls <- Ref.make(0)
        cleanup = Flaky(config, fibers, calls)
        _ <- ZIO.scoped(
          cleanup.start() *> TestClock.adjust(0.seconds) *> calls.get.flatMap(n => ZIO.succeed(n)) *> TestClock.adjust(1.hour) *> TestClock.adjust(
            1.hour,
          ),
        )
        total <- calls.get
      yield assertTrue(total == 3)
    },
    test("reports expired rows for every table with an index on expires_at, and says which are cleaned") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig("rep_cleaned")))
      for
        fibers <- Ref.make(List.empty[Fiber.Runtime[Throwable, Long]])
        counted <- Ref.make(List.empty[String])
        _ <- ZIO.scoped(Reporting(config, fibers, counted).start() *> TestClock.adjust(0.seconds))
        cleanedExpired <- gauge("cleanup_expired_rows", "rep_cleaned")
        forgottenExpired <- gauge("cleanup_expired_rows", "rep_forgotten")
        oldest <- gauge("cleanup_oldest_expired_age_seconds", "rep_forgotten")
        cleanedFlag <- gauge("cleanup_configured", "rep_cleaned")
        forgottenFlag <- gauge("cleanup_configured", "rep_forgotten")
        unindexedFlag <- gauge("cleanup_configured", "rep_unindexed")
        asked <- counted.get
      yield assertTrue(
        cleanedExpired == 7.0,
        forgottenExpired == 900.0,
        oldest == 42.0,
        cleanedFlag == 1.0,
        forgottenFlag == 0.0,
        unindexedFlag == 0.0,
        !asked.contains("rep_unindexed"),
      )
    },
    test("publishes a size estimate for each such table, and for users where the table exists") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig("rep_cleaned")))
      for
        fibers <- Ref.make(List.empty[Fiber.Runtime[Throwable, Long]])
        counted <- Ref.make(List.empty[String])
        _ <- ZIO.scoped(Reporting(config, fibers, counted).start() *> TestClock.adjust(0.seconds))
        indexed <- gauge("db_table_rows_estimate", "rep_cleaned")
        unindexed <- gauge("db_table_rows_estimate", "rep_unindexed")
        users <- gauge("db_table_rows_estimate", "users")
      yield assertTrue(indexed == 1000.0, unindexed == 1000.0, users == 0.0)
    },
    test("measures again once the stats interval elapses") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig("rep_cleaned")), statsInterval = 1.minute)
      for
        fibers <- Ref.make(List.empty[Fiber.Runtime[Throwable, Long]])
        counted <- Ref.make(List.empty[String])
        _ <- ZIO.scoped(Reporting(config, fibers, counted).start() *> TestClock.adjust(0.seconds) *> TestClock.adjust(1.minute))
        asked <- counted.get
      yield assertTrue(asked.count(_ == "rep_cleaned") == 2)
    },
    test("counts the rows it deletes, per table") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig("metrics_rows", batchSize = 2)))
      for
        before <- counter("cleanup_rows_deleted_total", "metrics_rows")
        (cleanup, _, _) <- manager(config, counts = List(2, 2, 1))
        _ <- ZIO.scoped(cleanup.start() *> TestClock.adjust(0.seconds))
        rows <- counter("cleanup_rows_deleted_total", "metrics_rows")
      yield assertTrue(rows - before == 5.0)
    },
    test("records when a table was last cleaned") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig("last_clean_rows")))
      for
        _ <- TestClock.setTime(java.time.Instant.ofEpochSecond(1_000_000))
        (cleanup, _, _) <- manager(config, counts = List(0))
        _ <- ZIO.scoped(cleanup.start() *> TestClock.adjust(0.seconds))
        at <- gauge("cleanup_last_success_timestamp_seconds", "last_clean_rows")
      yield assertTrue(at == 1_000_000.0)
    },
    test("drains a table in a single batch when fewer rows than the batch size are deleted") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig(batchSize = 10)))
      for
        (cleanup, calls, _) <- manager(config, counts = List(3))
        _ <- ZIO.scoped(cleanup.start() *> TestClock.adjust(0.seconds))
        recorded <- calls.get
      yield assertTrue(recorded == Chunk(Call("sessions", 10, "id")))
    },
    test("keeps draining while a batch deletes a full batch worth of rows") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig(batchSize = 2)))
      for
        (cleanup, calls, _) <- manager(config, counts = List(2, 2, 1))
        _ <- ZIO.scoped(cleanup.start() *> TestClock.adjust(0.seconds))
        recorded <- calls.get
      yield assertTrue(recorded.size == 3, recorded.forall(_.batchSize == 2))
    },
    test("passes the configured key column through to the batch") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig(keyColumn = Some("ctid"))))
      for
        (cleanup, calls, _) <- manager(config, counts = List(0))
        _ <- ZIO.scoped(cleanup.start() *> TestClock.adjust(0.seconds))
        recorded <- calls.get
      yield assertTrue(recorded.map(_.keyColumn) == Chunk("ctid"))
    },
    test("starts one job per configured table") {
      val config = CleanupConfig(
        maxThreads = 2,
        tables = List(tableConfig("sessions"), tableConfig("codes"), tableConfig("tokens")),
      )
      for
        (cleanup, calls, fibers) <- manager(config)
        _ <- ZIO.scoped(
          cleanup.start() *> TestClock.adjust(0.seconds) *> fibers.get.flatMap(f => ZIO.succeed(f)),
        )
        recorded <- calls.get
      yield assertTrue(recorded.map(_.tableName).toSet == Set("sessions", "codes", "tokens"))
    },
    test("repeats the drain once the table interval elapses") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig(interval = 1.hour)))
      for
        (cleanup, calls, _) <- manager(config)
        _ <- ZIO.scoped:
          for
            _ <- cleanup.start()
            _ <- TestClock.adjust(0.seconds)
            afterStart <- calls.get
            _ <- TestClock.adjust(1.hour)
            afterInterval <- calls.get
          yield assertTrue(afterStart.size == 1, afterInterval.size == 2)
        result <- calls.get.map(recorded => assertTrue(recorded.size == 2))
      yield result
    },
    test("stop interrupts the running jobs so no further batches run") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig(interval = 1.hour)))
      for
        (cleanup, calls, _) <- manager(config)
        recorded <- ZIO.scoped:
          for
            _ <- cleanup.start()
            _ <- TestClock.adjust(0.seconds)
            // stop() joins the jobs it just interrupted, which hands their interruption
            // to whoever called it, so run it on its own fiber and await the outcome
            // instead of letting that land on the test fiber.
            stopped <- cleanup.stop().forkDaemon
            _ <- stopped.await
            _ <- TestClock.adjust(5.hours)
            recorded <- calls.get
          yield recorded
      yield assertTrue(recorded.size == 1)
    },
    test("stop is a no-op when nothing was started") {
      val config = CleanupConfig(maxThreads = 1, tables = List(tableConfig()))
      for
        (cleanup, calls, _) <- manager(config)
        _ <- cleanup.stop()
        recorded <- calls.get
      yield assertTrue(recorded.isEmpty)
    },
  ) @@ TestAspect.silentLogging

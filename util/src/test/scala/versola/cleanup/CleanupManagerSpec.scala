package versola.cleanup

import zio.*
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

  def spec = suite("CleanupManager.Base")(
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
          cleanup.start() *> TestClock.adjust(0.seconds) *> fibers.get.flatMap(f => ZIO.succeed(f))
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

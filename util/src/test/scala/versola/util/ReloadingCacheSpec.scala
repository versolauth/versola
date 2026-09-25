package versola.util

import zio.*
import zio.test.*

import java.net.ConnectException
import java.sql.SQLException

object ReloadingCacheSpec extends ZIOSpecDefault:

  /** A source that fails `failures` times before answering.
    *
    * Counts its calls, which is what the retry rule is actually observable through: how long a
    * load is allowed to keep trying differs by the class of the failure, not by its message.
    */
  private def source(error: => Throwable, failures: Int, calls: Ref[Int]): CacheSource[Vector[String]] =
    new CacheSource[Vector[String]]:
      override def getAll: Task[Vector[String]] =
        calls.updateAndGet(_ + 1).flatMap: attempt =>
          if attempt > failures then ZIO.succeed(Vector("loaded")) else ZIO.fail(error)

  /** Runs an initial load against the test clock.
    *
    * The schedule sleeps between attempts, so nothing happens until the clock is moved. Stepping
    * it rather than jumping keeps the elapsed time the wait is bounded by roughly proportional to
    * the number of attempts made, the way it is in a real process.
    */
  private def load(cacheSource: CacheSource[Vector[String]]): ZIO[Any, Nothing, Exit[Throwable, Vector[String]]] =
    for
      fiber <- ZIO
        .scoped(ReloadingCache.make[Vector[String]](5.minutes).provideSome[Scope](ZLayer.succeed(cacheSource)))
        .flatMap(_.get)
        .fork
      _ <- (TestClock.adjust(500.millis) *> fiber.poll).repeatUntil(_.isDefined)
      exit <- fiber.await
    yield exit

  def spec = suite("ReloadingCache")(
    test("waits for a source that is only not up yet, well past the bounded retry") {
      for
        calls <- Ref.make(0)
        exit <- load(source(ConnectException("Connection refused"), failures = 20, calls))
        attempts <- calls.get
      yield assertTrue(exit == Exit.succeed(Vector("loaded")), attempts == 21)
    },
    test("treats a wrapped connect failure as not up yet") {
      for
        calls <- Ref.make(0)
        exit <- load(source(RuntimeException("request failed", ConnectException("refused")), failures = 20, calls))
        attempts <- calls.get
      yield assertTrue(exit == Exit.succeed(Vector("loaded")), attempts == 21)
    },
    test("treats a connection-class SQL failure as not up yet") {
      for
        calls <- Ref.make(0)
        exit <- load(source(SQLException("connection refused", "08001"), failures = 20, calls))
        attempts <- calls.get
      yield assertTrue(exit == Exit.succeed(Vector("loaded")), attempts == 21)
    },
    test("gives up on a source that never comes up, rather than waiting forever") {
      for
        calls <- Ref.make(0)
        exit <- load(source(ConnectException("Connection refused"), failures = Int.MaxValue, calls))
        attempts <- calls.get
      yield assertTrue(exit.isFailure, attempts > 7)
    },
    test("still fails fast on a failure it cannot classify") {
      for
        calls <- Ref.make(0)
        exit <- load(source(RuntimeException("malformed response"), failures = Int.MaxValue, calls))
        attempts <- calls.get
      yield assertTrue(exit.isFailure, attempts == 7)
    },
    test("an unclassifiable failure that clears is still absorbed") {
      for
        calls <- Ref.make(0)
        exit <- load(source(RuntimeException("malformed response"), failures = 6, calls))
        attempts <- calls.get
      yield assertTrue(exit == Exit.succeed(Vector("loaded")), attempts == 7)
    },
  )

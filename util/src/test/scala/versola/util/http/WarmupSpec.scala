package versola.util.http

import zio.*
import zio.test.*

object WarmupSpec extends ZIOSpecDefault:

  def spec = suite("Warmup")(
    test("runs the effect to completion within budget") {
      for
        ran <- Ref.make(false)
        _ <- Warmup.run(ran.set(true), 1.second)
        completed <- ran.get
      yield assertTrue(completed)
    },
    test("is bounded: an effect that never completes does not block past the budget") {
      for
        fiber <- Warmup.run(ZIO.never, 1.second).fork
        _ <- (TestClock.adjust(100.millis) *> fiber.poll).repeatUntil(_.isDefined)
        exit <- fiber.await
      yield assertTrue(exit.isSuccess) // reaching a successful exit is the assertion: run() returned
    },
    // The interruptible `ZIO.never` above is bounded by a bare `timeout` too; only a step that
    // cannot be interrupted tells the two apart. On the live clock because the assertion is about
    // wall time actually elapsed, and self-releasing after 2s so an orphaned uninterruptible
    // fiber cannot outlive the suite.
    test("is bounded even when the effect cannot be interrupted") {
      Live.live(
        for
          start <- Clock.nanoTime
          _ <- Warmup.run(ZIO.sleep(2.seconds).uninterruptible, 100.millis)
          elapsed <- Clock.nanoTime.map(_ - start)
        yield assertTrue(elapsed < 1.second.toNanos),
      )
    },
    test("is fail-open: a defect in the effect does not propagate") {
      for
        exit <- Warmup.run(ZIO.die(RuntimeException("boom")), 1.second).exit
      yield assertTrue(exit.isSuccess)
    },
    test("is fail-open: an interrupted effect does not propagate") {
      for
        exit <- Warmup.run(ZIO.interrupt, 1.second).exit
      yield assertTrue(exit.isSuccess)
    },
    test("a fast effect does not wait out the full budget") {
      for
        fiber <- Warmup.run(ZIO.unit, 1.day).fork
        result <- fiber.join.timeout(1.second)
      yield assertTrue(result.isDefined)
    },
  )

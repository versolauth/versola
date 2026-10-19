package versola.util.http

import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

object OutboundGuardSpec extends ZIOSpecDefault:

  private def settings(
      mode: GuardMode = GuardMode.Enforce,
      maxConcurrent: Int = 2,
      maxQueue: Int = 2,
      queueTimeout: Duration = 100.millis,
      requestTimeout: Duration = 5.seconds,
  ) = OutboundGuardSettings(mode, maxConcurrent, maxQueue, queueTimeout, requestTimeout)

  private def rejectedCount(peer: String, reason: String, mode: String) =
    Metric.counter("http_client_guard_rejected_total")
      .tagged(Set(MetricLabel("peer", peer), MetricLabel("reason", reason), MetricLabel("mode", mode)))
      .value.map(_.count)

  /** Starts `n` calls that hold their place until `gate` opens, returning once they all hold one. */
  private def occupy(guard: OutboundGuard, peer: String, n: Int, gate: Promise[Nothing, Unit]) =
    for
      started <- Ref.make(0)
      fibers <- ZIO.foreach(1 to n)(_ => guard.protect(peer)(started.update(_ + 1) *> gate.await).fork)
      _ <- started.get.repeatUntil(_ == n)
    yield fibers

  def spec = suite("OutboundGuard")(
    suite("settings")(
      test("nothing set: shadow mode, the pool's size, and the documented defaults") {
        val s = OutboundGuardSettings.parse(None, None, None, None, None, poolSize = 128)
        assertTrue(
          s == OutboundGuardSettings(GuardMode.Shadow, 128, 256, 1.second, 10.seconds),
        )
      },
      test("valid values are taken, in any case and with spaces") {
        val s = OutboundGuardSettings.parse(Some(" Enforce "), Some("64"), Some("32"), Some("500ms"), Some("3s"), poolSize = 128)
        assertTrue(s == OutboundGuardSettings(GuardMode.Enforce, 64, 32, 500.millis, 3.seconds))
      },
      test("an invalid value falls back to its default instead of failing the service") {
        val s = OutboundGuardSettings.parse(Some("block"), Some("0"), Some("-1"), Some("soon"), Some("0s"), poolSize = 16)
        assertTrue(s == OutboundGuardSettings(GuardMode.Shadow, 16, 256, 1.second, 10.seconds))
      },
    ),
    suite("enforce")(
      test("calls beyond the limit wait, and go on when a place is freed") {
        for
          guard <- OutboundGuard.make(settings(maxConcurrent = 1, queueTimeout = 5.seconds))
          gate <- Promise.make[Nothing, Unit]
          first <- occupy(guard, "p", 1, gate)
          second <- guard.protect("p")(ZIO.succeed("second")).fork
          _ <- ZIO.sleep(50.millis)
          before <- second.poll
          _ <- gate.succeed(())
          result <- second.join
          _ <- ZIO.foreachDiscard(first)(_.join)
        yield assertTrue(before.isEmpty, result == "second")
      },
      test("a call that cannot start within the queue timeout is rejected, and the metric counts it") {
        for
          guard <- OutboundGuard.make(settings(maxConcurrent = 1, queueTimeout = 50.millis))
          gate <- Promise.make[Nothing, Unit]
          _ <- occupy(guard, "timeout-peer", 1, gate)
          exit <- guard.protect("timeout-peer")(ZIO.unit).exit
          rejected <- rejectedCount("timeout-peer", "queue_timeout", "enforce")
          _ <- gate.succeed(())
        yield assertTrue(
          exit match
            case Exit.Failure(c) => c.failureOption.exists(_.isInstanceOf[OutboundOverloaded])
            case _ => false,
          rejected == 1.0,
        )
      },
      test("with the queue full, the next call is rejected at once, not after the timeout") {
        for
          guard <- OutboundGuard.make(settings(maxConcurrent = 1, maxQueue = 1, queueTimeout = 10.seconds))
          gate <- Promise.make[Nothing, Unit]
          holders <- occupy(guard, "full-peer", 1, gate)
          waiter <- guard.protect("full-peer")(ZIO.unit).fork
          _ <- ZIO.sleep(50.millis)
          start <- Clock.nanoTime
          exit <- guard.protect("full-peer")(ZIO.unit).exit
          took <- Clock.nanoTime.map(end => Duration.fromNanos(end - start))
          rejected <- rejectedCount("full-peer", "queue_full", "enforce")
          _ <- gate.succeed(())
          _ <- waiter.join
          _ <- ZIO.foreachDiscard(holders)(_.join)
        yield assertTrue(exit.isFailure, took < 1.second, rejected == 1.0)
      },
      test("a call that does not answer within the request timeout is abandoned with OutboundTimedOut") {
        for
          guard <- OutboundGuard.make(settings(requestTimeout = 50.millis))
          exit <- guard.protect("slow-peer")(ZIO.never).exit
          rejected <- rejectedCount("slow-peer", "request_timeout", "enforce")
        yield assertTrue(
          exit match
            case Exit.Failure(c) => c.failureOption.exists(_.isInstanceOf[OutboundTimedOut])
            case _ => false,
          rejected == 1.0,
        )
      },
      test("places are given back after a failure, a timeout and a rejection: nothing leaks") {
        for
          guard <- OutboundGuard.make(settings(maxConcurrent = 1, maxQueue = 0, queueTimeout = 50.millis, requestTimeout = 50.millis))
          _ <- guard.protect("leak-peer")(ZIO.fail(new RuntimeException("boom"))).ignore
          _ <- guard.protect("leak-peer")(ZIO.never).ignore
          // if either had kept its place this would be rejected
          ok <- guard.protect("leak-peer")(ZIO.succeed("ok"))
        yield assertTrue(ok == "ok")
      },
      test("a caller that gives up while waiting leaves the queue and loses nothing") {
        for
          guard <- OutboundGuard.make(settings(maxConcurrent = 1, queueTimeout = 10.seconds))
          gate <- Promise.make[Nothing, Unit]
          holders <- occupy(guard, "interrupt-peer", 1, gate)
          waiter <- guard.protect("interrupt-peer")(ZIO.unit).fork
          _ <- ZIO.sleep(50.millis)
          _ <- waiter.interrupt
          _ <- gate.succeed(())
          _ <- ZIO.foreachDiscard(holders)(_.join)
          // the interrupted waiter must not be holding a place, nor be handed one later
          again <- guard.protect("interrupt-peer")(ZIO.succeed("ok"))
          second <- guard.protect("interrupt-peer")(ZIO.succeed("ok"))
        yield assertTrue(again == "ok", second == "ok")
      },
      test("the places are per peer: a stuck peer does not hold back another") {
        for
          guard <- OutboundGuard.make(settings(maxConcurrent = 1, queueTimeout = 50.millis))
          gate <- Promise.make[Nothing, Unit]
          _ <- occupy(guard, "stuck", 1, gate)
          other <- guard.protect("healthy")(ZIO.succeed("ok"))
          _ <- gate.succeed(())
        yield assertTrue(other == "ok")
      },
      test("raising the limit while calls wait lets them in at once") {
        for
          guard <- OutboundGuard.make(settings(maxConcurrent = 1, queueTimeout = 10.seconds))
          gate <- Promise.make[Nothing, Unit]
          holders <- occupy(guard, "resize-peer", 1, gate)
          waiter <- guard.protect("resize-peer")(ZIO.succeed("through")).fork
          _ <- ZIO.sleep(50.millis)
          _ <- guard.update(settings(maxConcurrent = 2, queueTimeout = 10.seconds))
          result <- waiter.join.timeout(2.seconds)
          _ <- gate.succeed(())
          _ <- ZIO.foreachDiscard(holders)(_.join)
        yield assertTrue(result.contains("through"))
      },
    ),
    suite("shadow")(
      test("never rejects and never times out, but counts what enforce would have") {
        for
          guard <- OutboundGuard.make(settings(mode = GuardMode.Shadow, maxConcurrent = 1, queueTimeout = 30.millis, requestTimeout = 30.millis))
          gate <- Promise.make[Nothing, Unit]
          holders <- occupy(guard, "shadow-peer", 1, gate)
          waiter <- guard.protect("shadow-peer")(ZIO.sleep(60.millis).as("slow")).fork
          _ <- ZIO.sleep(80.millis) // the waiter has now waited longer than the queue timeout
          _ <- gate.succeed(())
          result <- waiter.join
          _ <- ZIO.foreachDiscard(holders)(_.join)
          wouldQueue <- rejectedCount("shadow-peer", "queue_timeout", "shadow")
          wouldRequest <- rejectedCount("shadow-peer", "request_timeout", "shadow")
        yield assertTrue(result == "slow", wouldQueue == 1.0, wouldRequest >= 1.0)
      },
    ),
    suite("off")(
      test("passes the call straight through") {
        for
          guard <- OutboundGuard.make(settings(mode = GuardMode.Off, maxConcurrent = 1))
          gate <- Promise.make[Nothing, Unit]
          started <- Ref.make(0)
          fibers <- ZIO.foreach(1 to 5)(_ => guard.protect("off-peer")(started.update(_ + 1) *> gate.await).fork)
          _ <- started.get.repeatUntil(_ == 5)
          _ <- gate.succeed(())
          _ <- ZIO.foreachDiscard(fibers)(_.join)
        yield assertTrue(true)
      },
    ),
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential

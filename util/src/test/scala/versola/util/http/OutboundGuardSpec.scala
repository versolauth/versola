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

  /** A guard as the running service has it: armed, nothing exempt. */
  private def armed(s: OutboundGuardSettings): UIO[OutboundGuard] = OutboundGuard.make(s).tap(_.arm(Set.empty))

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
          guard <- armed(settings(maxConcurrent = 1, queueTimeout = 5.seconds))
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
          guard <- armed(settings(maxConcurrent = 1, queueTimeout = 50.millis))
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
          guard <- armed(settings(maxConcurrent = 1, maxQueue = 1, queueTimeout = 10.seconds))
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
          guard <- armed(settings(requestTimeout = 50.millis))
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
          guard <- armed(settings(maxConcurrent = 1, maxQueue = 0, queueTimeout = 50.millis, requestTimeout = 50.millis))
          _ <- guard.protect("leak-peer")(ZIO.fail(new RuntimeException("boom"))).ignore
          _ <- guard.protect("leak-peer")(ZIO.never).ignore
          // if either had kept its place this would be rejected
          ok <- guard.protect("leak-peer")(ZIO.succeed("ok"))
        yield assertTrue(ok == "ok")
      },
      test("a caller that gives up while waiting leaves the queue and loses nothing") {
        for
          guard <- armed(settings(maxConcurrent = 1, queueTimeout = 10.seconds))
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
          guard <- armed(settings(maxConcurrent = 1, queueTimeout = 50.millis))
          gate <- Promise.make[Nothing, Unit]
          _ <- occupy(guard, "stuck", 1, gate)
          other <- guard.protect("healthy")(ZIO.succeed("ok"))
          _ <- gate.succeed(())
        yield assertTrue(other == "ok")
      },
      test("raising the limit while calls wait lets them in at once") {
        for
          guard <- armed(settings(maxConcurrent = 1, queueTimeout = 10.seconds))
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
          guard <- armed(settings(mode = GuardMode.Shadow, maxConcurrent = 1, queueTimeout = 30.millis, requestTimeout = 30.millis))
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
    suite("circuit breaker")(
      test("opens after enough failures, refuses at once without calling the peer, and closes after successful probes") {
        val breaker = BreakerSettings(enabled = true, failureRatio = 0.5, minCalls = 4, window = 10.seconds, openFor = 200.millis, probeCalls = 1)
        for
          guard <- armed(settings(maxConcurrent = 10).copy(breaker = breaker))
          _ <- guard.arm(Set.empty)
          _ <- ZIO.foreachDiscard(1 to 4)(_ => guard.protect("cb-peer")(ZIO.fail(new RuntimeException("down"))).ignore)
          calls <- Ref.make(0)
          refused <- guard.protect("cb-peer")(calls.update(_ + 1)).exit
          untouched <- calls.get
          _ <- ZIO.sleep(300.millis)
          probe <- guard.protect("cb-peer")(calls.update(_ + 1).as("probe")).exit
          after <- guard.protect("cb-peer")(ZIO.succeed("after")).exit
          counted <- rejectedCount("cb-peer", "circuit_open", "enforce")
        yield assertTrue(
          refused match
            case Exit.Failure(c) => c.failureOption.exists(e => e.isInstanceOf[OutboundOverloaded] && e.getMessage.contains("circuit open"))
            case _ => false,
          untouched == 0,
          probe == Exit.succeed("probe"),
          after == Exit.succeed("after"),
          counted == 1.0,
        )
      },
      test("a response the caller names a failure of the peer counts like an error") {
        val breaker = BreakerSettings(enabled = true, failureRatio = 0.5, minCalls = 3, window = 10.seconds, openFor = 5.seconds, probeCalls = 1)
        for
          guard <- armed(settings(maxConcurrent = 10).copy(breaker = breaker))
          _ <- guard.arm(Set.empty)
          _ <- ZIO.foreachDiscard(1 to 3)(_ => guard.protect[Any, Throwable, Int]("status-peer", _ == 503)(ZIO.succeed(503)))
          refused <- guard.protect[Any, Throwable, Int]("status-peer", _ == 503)(ZIO.succeed(200)).exit
        yield assertTrue(refused.isFailure)
      },
      test("a refusal by the guard itself is not a failure of the peer") {
        val breaker = BreakerSettings(enabled = true, failureRatio = 0.5, minCalls = 2, window = 10.seconds, openFor = 5.seconds, probeCalls = 1)
        for
          guard <- armed(settings(maxConcurrent = 1, maxQueue = 1, queueTimeout = 20.millis).copy(breaker = breaker))
          _ <- guard.arm(Set.empty)
          gate <- Promise.make[Nothing, Unit]
          holders <- occupy(guard, "own-peer", 1, gate)
          _ <- ZIO.foreachDiscard(1 to 4)(_ => guard.protect("own-peer")(ZIO.unit).ignore)
          _ <- gate.succeed(())
          _ <- ZIO.foreachDiscard(holders)(_.join)
          next <- guard.protect("own-peer")(ZIO.succeed("ok")).exit
        yield assertTrue(next == Exit.succeed("ok"))
      },
      test("in shadow nothing is refused, and what would have been is counted") {
        val breaker = BreakerSettings(enabled = true, failureRatio = 0.5, minCalls = 3, window = 10.seconds, openFor = 5.seconds, probeCalls = 1)
        for
          guard <- armed(settings(mode = GuardMode.Shadow, maxConcurrent = 10).copy(breaker = breaker))
          _ <- ZIO.foreachDiscard(1 to 3)(_ => guard.protect("shadow-cb")(ZIO.fail(new RuntimeException("down"))).ignore)
          results <- ZIO.foreach(1 to 3)(_ => guard.protect("shadow-cb")(ZIO.succeed("through")).exit)
          would <- rejectedCount("shadow-cb", "circuit_open", "shadow")
        yield assertTrue(results.forall(_ == Exit.succeed("through")), would == 3.0)
      },
    ),
    suite("arming and exempt peers")(
      test("until armed, enforce does not refuse; once armed it does, except towards exempt peers") {
        for
          guard <- OutboundGuard.make(settings(maxConcurrent = 1, maxQueue = 1, queueTimeout = 40.millis))
          gate <- Promise.make[Nothing, Unit]
          holders <- occupy(guard, "armed-peer", 1, gate)
          waiter <- guard.protect("armed-peer")(ZIO.succeed("waited")).fork
          _ <- ZIO.sleep(120.millis) // well past the queue timeout
          stillWaiting <- waiter.poll
          _ <- gate.succeed(())
          waited <- waiter.join
          _ <- ZIO.foreachDiscard(holders)(_.join)
          _ <- guard.arm(Set("exempt-peer"))
          gate2 <- Promise.make[Nothing, Unit]
          holders2 <- occupy(guard, "armed-peer", 1, gate2)
          rejected <- guard.protect("armed-peer")(ZIO.unit).exit
          gate3 <- Promise.make[Nothing, Unit]
          exemptHolders <- occupy(guard, "exempt-peer", 3, gate3)
          _ <- gate2.succeed(())
          _ <- gate3.succeed(())
          _ <- ZIO.foreachDiscard(holders2 ++ exemptHolders)(_.join)
        yield assertTrue(stillWaiting.isEmpty, waited == "waited", rejected.isFailure)
      },
    ),
    suite("retry budget")(
      test("a retry is made while the budget has a token, and the failure is returned when it has not") {
        val budget = RetryBudgetSettings(ratio = 1.0, minPerSecond = 0.0)
        for
          guard <- OutboundGuard.make(settings().copy(retryBudget = budget))
          attempts <- Ref.make(0)
          result <- guard.retrying[Any, String, Int]("retry-peer", maxRetries = 5)(_ => true)(attempts.updateAndGet(_ + 1).flatMap(n =>
            ZIO.fail(s"fail $n"),
          )).exit
          made <- attempts.get
          denied <- Metric.counter("http_client_retry_budget_denied_total")
            .tagged(Set(MetricLabel("peer", "retry-peer"), MetricLabel("mode", "enforce"))).value.map(_.count)
        yield assertTrue(result == Exit.fail("fail 2"), made == 2, denied == 1.0)
      },
      test("a success needs no retry, a failure that is not retryable is not retried, and shadow only counts") {
        val none = RetryBudgetSettings(ratio = 0.0, minPerSecond = 0.0)
        for
          enforced <- OutboundGuard.make(settings().copy(retryBudget = none))
          shadowed <- OutboundGuard.make(settings(mode = GuardMode.Shadow).copy(retryBudget = none))
          a <- Ref.make(0)
          ok <- enforced.retrying[Any, String, String]("ok-peer")(_ => true)(a.update(_ + 1).as("ok")).exit
          b <- Ref.make(0)
          notRetryable <- enforced.retrying[Any, String, Int]("nr-peer")(_ => false)(b.update(_ + 1) *> ZIO.fail("fatal")).exit
          c <- Ref.make(0)
          shadow <- shadowed.retrying[Any, String, Int]("shadow-retry", maxRetries = 2)(_ => true)(c.update(_ + 1) *> ZIO.fail("x")).exit
          calls <- ZIO.collectAll(List(a.get, b.get, c.get))
        yield assertTrue(ok == Exit.succeed("ok"), notRetryable == Exit.fail("fatal"), shadow == Exit.fail("x"), calls == List(1, 1, 3))
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

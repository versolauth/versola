package versola.util.http

import zio.*
import zio.test.*

object CircuitBreakerSpec extends ZIOSpecDefault:

  private val settings = BreakerSettings(enabled = true, failureRatio = 0.5, minCalls = 10, window = 10.seconds, openFor = 5.seconds, probeCalls = 2)
  private def sec(n: Long) = n * 1_000_000_000L

  /** `n` calls at `now`, the last `failed` of them failing. */
  private def calls(b: CircuitBreaker, now: Long, n: Int, failed: Int) =
    (1 to n).foreach: i =>
      val permit = b.acquire(now, settings)
      b.record(now, settings, permit, failed = i > n - failed)

  def spec = suite("CircuitBreaker")(
    suite("closed")(
      test("calls go, and failures below the ratio leave it closed") {
        val b = CircuitBreaker()
        calls(b, sec(1), 20, failed = 5)
        assertTrue(b.stateCode == 0, b.acquire(sec(1), settings) == Permit.Closed)
      },
      test("it opens at the failure ratio once there are enough calls, not before") {
        val b = CircuitBreaker()
        calls(b, sec(1), 9, failed = 9)
        val before = b.stateCode
        calls(b, sec(1), 1, failed = 1)
        assertTrue(before == 0, b.stateCode == 2)
      },
      test("old failures fall out of the window") {
        val b = CircuitBreaker()
        calls(b, sec(1), 8, failed = 8)
        // twenty seconds later: a window and more
        calls(b, sec(21), 10, failed = 0)
        calls(b, sec(21), 2, failed = 2)
        assertTrue(b.stateCode == 0)
      },
    ),
    suite("open")(
      test("calls are refused while it is open") {
        val b = CircuitBreaker()
        calls(b, sec(1), 10, failed = 10)
        assertTrue(b.acquire(sec(2), settings) == Permit.Refused, b.acquire(sec(5), settings) == Permit.Refused)
      },
      test("after the open time, a few probes go and the rest are refused") {
        val b = CircuitBreaker()
        calls(b, sec(1), 10, failed = 10)
        val first = b.acquire(sec(7), settings)
        val second = b.acquire(sec(7), settings)
        val third = b.acquire(sec(7), settings)
        assertTrue(first == Permit.Probe, second == Permit.Probe, third == Permit.Refused, b.stateCode == 1)
      },
      test("successful probes close it, and the failures of before no longer count") {
        val b = CircuitBreaker()
        calls(b, sec(1), 10, failed = 10)
        val p1 = b.acquire(sec(7), settings)
        val p2 = b.acquire(sec(7), settings)
        b.record(sec(7), settings, p1, failed = false)
        val half = b.stateCode
        b.record(sec(7), settings, p2, failed = false)
        calls(b, sec(8), 5, failed = 0)
        assertTrue(half == 1, b.stateCode == 0, b.acquire(sec(8), settings) == Permit.Closed)
      },
      test("a failed probe opens it again for a whole open time") {
        val b = CircuitBreaker()
        calls(b, sec(1), 10, failed = 10)
        val p = b.acquire(sec(7), settings)
        b.record(sec(7), settings, p, failed = true)
        assertTrue(b.stateCode == 2, b.acquire(sec(8), settings) == Permit.Refused, b.acquire(sec(12), settings) == Permit.Probe)
      },
      test("a probe that ended without an answer gives its place back") {
        val b = CircuitBreaker()
        calls(b, sec(1), 10, failed = 10)
        val p1 = b.acquire(sec(7), settings)
        val p2 = b.acquire(sec(7), settings)
        val full = b.acquire(sec(7), settings)
        b.abandon(p1)
        assertTrue(full == Permit.Refused, b.acquire(sec(7), settings) == Permit.Probe, p2 == Permit.Probe)
      },
      test("a call that began while closed and ended after it opened changes nothing") {
        val b = CircuitBreaker()
        val late = b.acquire(sec(1), settings)
        calls(b, sec(1), 10, failed = 10)
        b.record(sec(2), settings, late, failed = false)
        assertTrue(b.stateCode == 2)
      },
    ),
    suite("retry budget")(
      test("calls fill it by the ratio, and a retry costs a whole token") {
        val budget = RetryBudget()
        val s = RetryBudgetSettings(ratio = 0.1, minPerSecond = 0.0)
        (1 to 30).foreach(_ => budget.deposit(sec(1), s))
        val allowed = (1 to 10).count(_ => budget.tryWithdraw(sec(1), s))
        assertTrue(allowed == 3)
      },
      test("with no calls, nothing is allowed unless a trickle is configured") {
        val budget = RetryBudget()
        val none = RetryBudgetSettings(ratio = 0.1, minPerSecond = 0.0)
        val trickle = RetryBudgetSettings(ratio = 0.1, minPerSecond = 5.0)
        val denied = !budget.tryWithdraw(sec(1), none)
        val budget2 = RetryBudget()
        budget2.deposit(sec(1), trickle)
        val allowedAfterASecond = (1 to 10).count(_ => budget2.tryWithdraw(sec(2), trickle))
        assertTrue(denied, allowedAfterASecond == 5)
      },
      test("the budget is capped: a long quiet period does not store up a flood of retries") {
        val budget = RetryBudget()
        val s = RetryBudgetSettings(ratio = 0.1, minPerSecond = 5.0)
        budget.deposit(sec(1), s)
        val allowed = (1 to 1000).count(_ => budget.tryWithdraw(sec(3600), s))
        assertTrue(allowed == 50)
      },
    ),
  )

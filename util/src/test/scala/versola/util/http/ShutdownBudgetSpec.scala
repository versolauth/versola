package versola.util.http

import versola.util.audit.AuditEvents
import zio.*
import zio.test.*

/** The finalizers of a shutdown share the hook's [[VersolaApp.GracefulShutdownTimeout]]: each drain waits for at most what is left of it. */
object ShutdownBudgetSpec extends ZIOSpecDefault:

  def spec = suite("ShutdownBudget")(
    test("before the shutdown began a drain gets its own bound unchanged") {
      for allowed <- ShutdownBudget.allowed(10.seconds)
      yield assertTrue(allowed == 10.seconds)
    },
    test("the time the finalizers share is the hook's, less a margin that is left unspent") {
      assertTrue(
        ShutdownBudget.Total == VersolaApp.GracefulShutdownTimeout.minus(ShutdownBudget.Margin),
        ShutdownBudget.Total > Duration.Zero,
      )
    },
    test("a drain gets its own bound while enough is left, and what is left after that, down to nothing") {
      for
        _ <- ShutdownBudget.begin
        first <- ShutdownBudget.allowed(5.seconds)
        _ <- TestClock.adjust(10.seconds)
        second <- ShutdownBudget.allowed(5.seconds)
        _ <- TestClock.adjust(2.seconds)
        third <- ShutdownBudget.allowed(5.seconds)
        _ <- TestClock.adjust(10.seconds)
        last <- ShutdownBudget.allowed(5.seconds)
      yield assertTrue(
        first == 5.seconds,
        second == 3.seconds, // 13 s are shared; 10 s have passed
        third == 1.second,   // not 5: 12 s have passed
        last == Duration.Zero,
      )
    } @@ TestAspect.after(ZIO.succeed(ShutdownBudget.reset())),
    test("begin starts the clock once: a second call does not give the drains more time") {
      for
        _ <- ShutdownBudget.begin
        _ <- TestClock.adjust(8.seconds)
        _ <- ShutdownBudget.begin
        allowed <- ShutdownBudget.allowed(10.seconds)
      yield assertTrue(allowed == 5.seconds)
    } @@ TestAspect.after(ZIO.succeed(ShutdownBudget.reset())),
    test("within abandons a drain that outlasts what is left, and a drain with nothing left does not start") {
      for
        _ <- ShutdownBudget.begin
        _ <- TestClock.adjust(10.seconds)
        slow <- ShutdownBudget.within(5.seconds)(ZIO.sleep(1.hour).as("done")).fork
        _ <- TestClock.adjust(3.seconds)
        abandoned <- slow.join
        _ <- TestClock.adjust(10.seconds)
        ran <- Ref.make(false)
        none <- ShutdownBudget.within(5.seconds)(ran.set(true).as("done"))
        didRun <- ran.get
      yield assertTrue(abandoned.isEmpty, none.isEmpty, !didRun)
    } @@ TestAspect.after(ZIO.succeed(ShutdownBudget.reset())),
    test("the finalizers of auth, each at its own bound, would not fit the hook, which is what the shared budget is for") {
      // Worst case, a database that is down: every drain waits its whole bound. The numbers are those of the finalizers
      // (BackChannelOutbox 10 s, the audit stop 1 s + 5 s, the antifraud events, lists and counters 5 s each).
      val own = 10.seconds + 1.second + 5.seconds + 5.seconds + 5.seconds + 5.seconds
      assertTrue(own > VersolaApp.GracefulShutdownTimeout, ShutdownBudget.Total <= VersolaApp.GracefulShutdownTimeout)
    },
    test(
      "what is outside the shared budget fits the margin: the server's wait is inside it, the writer's stop wait and the secondary servers' are not",
    ) {
      // The main server's wait comes out of Total (the clock starts before the server stops), so the drains see Total less ServerDrainTimeout.
      val worstCase = ShutdownBudget.Total
        .plus(AuditEvents.WriterStopWait)
        .plus(VersolaApp.SecondaryServerDrainTimeout.multipliedBy(3))
      assertTrue(
        VersolaApp.ServerDrainTimeout < ShutdownBudget.Total,
        worstCase <= VersolaApp.GracefulShutdownTimeout,
      )
    },
  ) @@ TestAspect.sequential

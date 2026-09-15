package versola.loadgen.scenario

import zio.*
import zio.test.*

/** §7.1's "one virtual user has at most one in-flight operation", which is what keeps a campaign
  * from putting a second session on a user mid-refresh and provoking the reuse detection itself.
  */
object BusyUsersSpec extends ZIOSpecDefault:

  def spec = suite("BusyUsers")(
    test("a second arrival for a busy user is skipped rather than queued") {
      for
        busy <- BusyUsers.make
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        first <- busy.withUser(1L)(started.succeed(()) *> release.await).fork
        _ <- started.await
        second <- busy.withUser(1L)(ZIO.succeed("ran"))
        inFlight <- busy.size
        _ <- release.succeed(())
        _ <- first.join
        afterwards <- busy.size
      yield assertTrue(second.isEmpty, inFlight == 1, afterwards == 0)
    },
    test("different users do not block each other") {
      for
        busy <- BusyUsers.make
        release <- Promise.make[Nothing, Unit]
        held <- busy.withUser(1L)(release.await).fork
        _ <- busy.size.repeatUntil(_ == 1)
        other <- busy.withUser(2L)(ZIO.succeed("ran"))
        _ <- release.succeed(())
        _ <- held.join
      yield assertTrue(other.contains("ran"))
    },
    // A user left marked busy by a failed or interrupted session would never arrive again, which
    // shrinks the active population silently over a long campaign.
    test("the user is released when the session fails or is interrupted") {
      for
        busy <- BusyUsers.make
        _ <- busy.withUser(1L)(ZIO.fail("boom")).either
        afterFailure <- busy.size
        started <- Promise.make[Nothing, Unit]
        fiber <- busy.withUser(1L)(started.succeed(()) *> ZIO.never).fork
        _ <- started.await
        _ <- fiber.interrupt
        afterInterruption <- busy.size
      yield assertTrue(afterFailure == 0, afterInterruption == 0)
    },
    test("a user released by one session can be picked again by the next") {
      for
        busy <- BusyUsers.make
        first <- busy.withUser(1L)(ZIO.succeed(1))
        second <- busy.withUser(1L)(ZIO.succeed(2))
      yield assertTrue(first.contains(1), second.contains(2))
    },
  )

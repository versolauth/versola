package versola.loadgen.driver

import zio.*
import zio.test.*

/** A finished campaign must end the process with exit code 0. The driver races its work against
  * the server, which never returns; the loser's interruption surfaced as the app's exit cause
  * (logged as an ERROR, exit code 1) and the kubelet restarted every driver with back-off.
  */
object DriverExitSpec extends ZIOSpecDefault:
  private val stopped = InterruptedException("Interrupted by thread \"zio-fiber-1\"")

  def spec = suite("Driver.endedByInterruption")(
    test("an interrupt, and the InterruptedException it surfaces as, end a campaign cleanly") {
      assertTrue(
        Driver.endedByInterruption(Cause.interrupt(FiberId.None)),
        Driver.endedByInterruption(Cause.fail(stopped)),
        Driver.endedByInterruption(Cause.die(stopped)),
        Driver.endedByInterruption(Cause.fail(stopped) ++ Cause.die(stopped)),
      )
    },
    test("anything else is a failure, alone or mixed in") {
      assertTrue(
        !Driver.endedByInterruption(Cause.fail(RuntimeException("boom"))),
        !Driver.endedByInterruption(Cause.die(RuntimeException("boom"))),
        !Driver.endedByInterruption(Cause.fail(stopped) ++ Cause.fail(RuntimeException("boom"))),
        !Driver.endedByInterruption(Cause.empty),
      )
    },
  )

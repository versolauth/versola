package versola.util.http

import zio.*
import zio.test.*

import java.util.concurrent.Semaphore

/** [[ShutdownBudget]] is one clock for the whole JVM, and sbt runs the spec classes of `util` side by side in it: one spec's `begin`
  * or `reset` landed between another's `begin` and its reading (a drain allowed 5 s where 3 s were expected; "not begun" where it had
  * begun). A test that begins, reads or resets the clock holds this for its whole length, so none of them sees another's clock.
  */
object ShutdownBudgetExclusive:
  private val lock = new Semaphore(1)

  val aspect =
    TestAspect.aroundWith(ZIO.attemptBlocking(lock.acquire()).orDie)(_ => ZIO.succeed(lock.release()))

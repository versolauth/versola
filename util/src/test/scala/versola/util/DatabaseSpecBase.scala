package versola.util

import com.augustnagro.magnum.magzio.TransactorZIO
import org.scalamock.stubs.ZIOStubs
import zio.*
import zio.test.*

trait DatabaseSpecBase[E: Tag] extends ZIOStubs { self: ZIOSpec[TransactorZIO] =>

  def environment: ZLayer[TransactorZIO & TestEnvironment & Scope, Throwable, E]

  override final val spec: Spec[TransactorZIO & TestEnvironment & Scope, Any] = {
    val name = this.getClass.getSimpleName.stripSuffix("$")
    suite(name)(
      ZIO.serviceWith[E] { env =>
        (testCases(env) ++ implementationCases(env)).map(_ @@ TestAspect.before(beforeEach(env)))
      },
    )
      .@@(TestAspect.sequential)
      .@@(TestAspect.timed)
      .@@(TestAspect.timeout(testTimeout))
      .provideSomeLayerShared(environment)
  }

  def beforeEach(env: E): ZIO[TransactorZIO & TestEnvironment & Scope, Throwable, Unit]

  /** The behaviour every implementation is held to. */
  def testCases(env: E): List[Spec[E & Scope, Any]]

  /** What only this implementation promises (a statement timeout, a lock it gives way to, a partition it keeps): the
    * runner adds them, so that the behaviour spec stays one another storage can run as it is. */
  def implementationCases(env: E): List[Spec[E & TransactorZIO & Scope, Any]] = Nil

  def testTimeout = 10.seconds
}

package versola.util

import com.augustnagro.magnum.magzio.TransactorZIO
import org.scalamock.stubs.ZIOStubs
import zio.*
import zio.test.*

trait DatabaseSpecBase[E: Tag] extends ZIOStubs { self: ZIOSpec[TransactorZIO] =>

  def environment: ZLayer[TransactorZIO & TestEnvironment & Scope, Throwable, E]

  override final val spec: Spec[TransactorZIO & TestEnvironment & Scope, Any] = {
    suite(this.getClass.getSimpleName.stripSuffix("$"))(
      ZIO.serviceWith[E] { env =>
        testCases(env).map(_ @@ TestAspect.before(beforeEach(env)))
      }
    )
      .@@(TestAspect.sequential)
      .@@(TestAspect.timed)
      .@@(TestAspect.timeout(testTimeout))
      .provideSomeLayerShared(environment)
  }

  def beforeEach(env: E): ZIO[TransactorZIO & TestEnvironment & Scope, Throwable, Unit]

  def testCases(env: E): List[Spec[E & Scope, Any]]

  def testTimeout = 10.seconds

}

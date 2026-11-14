package versola.central.audit

import zio.*
import zio.test.*

object DenialThrottleSpec extends ZIOSpecDefault:

  def spec = suite("DenialThrottle")(
    test("admits a key once in its interval, whatever the number of tries, and again when the interval is over") {
      val throttle = TestCertAuditState.throttle(1.minute)
      for
        first <- throttle.admit("edge-a")
        floods <- ZIO.foreach(1 to 100)(_ => throttle.admit("edge-a"))
        _ <- TestClock.adjust(59.seconds)
        stillWithin <- throttle.admit("edge-a")
        _ <- TestClock.adjust(2.seconds)
        after <- throttle.admit("edge-a")
      yield assertTrue(first, !floods.exists(identity), !stillWithin, after)
    },
    test("the interval of a key starts at its first event: tries within it do not extend it") {
      val throttle = TestCertAuditState.throttle(1.minute)
      for
        _ <- throttle.admit("edge-a")
        _ <- TestClock.adjust(40.seconds)
        _ <- throttle.admit("edge-a")
        _ <- TestClock.adjust(21.seconds)
        after <- throttle.admit("edge-a")
      yield assertTrue(after)
    },
    test("keys are independent") {
      val throttle = TestCertAuditState.throttle(1.minute)
      for
        a <- throttle.admit("edge-a")
        b <- throttle.admit("edge-b")
        aAgain <- throttle.admit("edge-a")
      yield assertTrue(a, b, !aAgain)
    },
    test("what it keeps is dropped as it expires, so it holds only the keys of the last interval") {
      val throttle = TestCertAuditState.throttle(1.minute)
      for
        _ <- ZIO.foreachDiscard(1 to 100)(i => throttle.admit(s"edge-$i"))
        many <- throttle.tracked
        _ <- TestClock.adjust(61.seconds)
        _ <- throttle.admit("edge-1")
        few <- throttle.tracked
      yield assertTrue(many.size == 100, few == Set("edge-1"))
    },
  )

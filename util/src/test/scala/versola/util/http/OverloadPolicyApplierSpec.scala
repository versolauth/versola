package versola.util.http

import zio.*
import zio.test.*

object OverloadPolicyApplierSpec extends ZIOSpecDefault:

  private val inboundBase = InboundAdmissionSettings(GuardMode.Shadow, 256, 0.85, 0.65, 1)
  private val outboundBase = OutboundGuardSettings(GuardMode.Shadow, 128, 256, 1.second, 10.seconds)

  private val enforcing = OverloadPolicy(
    inbound = Some(InboundPolicy(
      mode = Some("enforce"),
      maxInFlight = Some(64),
      continuationShare = None,
      startShare = None,
      retryAfterSeconds = None,
      adaptive = None,
    )),
    outbound = Some(OutboundPolicy(
      mode = Some("enforce"),
      requestTimeoutMillis = Some(2000),
      maxConcurrent = None,
      maxQueue = None,
      queueTimeoutMillis = None,
      breaker = None,
      retryBudget = None,
    )),
  )

  private def fixture =
    for
      admission <- InboundAdmission.make(inboundBase)
      guard <- OutboundGuard.make(outboundBase)
    yield (admission, guard)

  def spec = suite("OverloadPolicyApplier")(
    test("a valid policy is applied to both, over the base settings") {
      for
        (admission, guard) <- fixture
        result <- OverloadPolicyApplier(Some(enforcing), admission, guard, inboundBase, outboundBase)
        outbound <- guard.current
      yield assertTrue(
        result == Right(()),
        admission.current.mode == GuardMode.Enforce,
        admission.current.maxInFlight == 64,
        admission.current.continuationShare == 0.85,
        outbound.mode == GuardMode.Enforce,
        outbound.requestTimeout == 2.seconds,
        outbound.maxConcurrent == 128,
      )
    },
    test("removing the policy returns both to the settings the process started with") {
      for
        (admission, guard) <- fixture
        _ <- OverloadPolicyApplier(Some(enforcing), admission, guard, inboundBase, outboundBase)
        _ <- OverloadPolicyApplier(None, admission, guard, inboundBase, outboundBase)
        outbound <- guard.current
      yield assertTrue(admission.current == inboundBase, outbound == outboundBase)
    },
    test("a policy that does not validate is not applied, and what was in force stays") {
      val bad = OverloadPolicy(
        inbound = Some(InboundPolicy(
          maxInFlight = Some(0),
          mode = None,
          continuationShare = None,
          startShare = None,
          retryAfterSeconds = None,
          adaptive = None,
        )),
        outbound = None,
      )
      for
        (admission, guard) <- fixture
        _ <- OverloadPolicyApplier(Some(enforcing), admission, guard, inboundBase, outboundBase)
        result <- OverloadPolicyApplier(Some(bad), admission, guard, inboundBase, outboundBase)
        outbound <- guard.current
      yield assertTrue(
        result.left.exists(_.contains("inbound.maxInFlight")),
        admission.current.maxInFlight == 64,
        outbound.mode == GuardMode.Enforce,
      )
    },
    test("run follows the source: a change is applied within a second or so, and an invalid one is ignored") {
      for
        (admission, guard) <- fixture
        source <- Ref.make(Option.empty[OverloadPolicy])
        fiber <- OverloadPolicyApplier.run(source.get, admission, guard, inboundBase, outboundBase).fork
        _ <- TestClock.adjust(0.seconds)
        _ <- source.set(Some(enforcing))
        _ <- TestClock.adjust(1.second)
        applied = admission.current.maxInFlight
        _ <- source.set(Some(OverloadPolicy(
          inbound = Some(InboundPolicy(
            maxInFlight = Some(-5),
            mode = None,
            continuationShare = None,
            startShare = None,
            retryAfterSeconds = None,
            adaptive = None,
          )),
          outbound = None,
        )))
        _ <- TestClock.adjust(1.second)
        _ <- fiber.interrupt
      yield assertTrue(applied == 64, admission.current.maxInFlight == 64)
    },
  ) @@ TestAspect.timeout(30.seconds) @@ TestAspect.silentLogging

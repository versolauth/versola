package versola.util.http

import zio.*
import zio.test.*

/** Applies into a `Ref`, not `Observability.accessLog`: that one is process-wide and `ObservabilitySpec` runs beside this. */
object AccessLogPolicyApplierSpec extends ZIOSpecDefault:

  private val base = AccessLogSettings(AccessLogMode.Off, slowMillis = 500, successSampleRate = 0.05)

  private val sampled = AccessLogPolicy(mode = Some("sampled"), successSampleRate = Some(0.5), slowMillis = None)

  def spec = suite("AccessLogPolicyApplier")(
    test("a valid policy is applied over the base settings") {
      for
        current <- Ref.make(base)
        result <- AccessLogPolicyApplier(Some(sampled), base, current.set)
        now <- current.get
      yield assertTrue(result == Right(()), now == AccessLogSettings(AccessLogMode.Sampled, 500, 0.5))
    },
    test("removing the policy returns to the settings the process started with") {
      for
        current <- Ref.make(base)
        _ <- AccessLogPolicyApplier(Some(sampled), base, current.set)
        _ <- AccessLogPolicyApplier(None, base, current.set)
        now <- current.get
      yield assertTrue(now == base)
    },
    test("a policy that does not validate is not applied, and what was in force stays") {
      for
        current <- Ref.make(base)
        _ <- AccessLogPolicyApplier(Some(sampled), base, current.set)
        result <- AccessLogPolicyApplier(Some(AccessLogPolicy(successSampleRate = Some(2.0), mode = None, slowMillis = None)), base, current.set)
        now <- current.get
      yield assertTrue(result.left.exists(_.contains("successSampleRate")), now.mode == AccessLogMode.Sampled)
    },
    test("run follows the source: a change is applied within a second or so, and an invalid one is ignored") {
      for
        current <- Ref.make(base)
        source <- Ref.make(Option.empty[AccessLogPolicy])
        fiber <- AccessLogPolicyApplier.run(source.get, base, current.set).fork
        _ <- TestClock.adjust(0.seconds)
        _ <- source.set(Some(AccessLogPolicy(mode = Some("full"), slowMillis = None, successSampleRate = None)))
        _ <- TestClock.adjust(1.second)
        applied <- current.get
        _ <- source.set(Some(AccessLogPolicy(mode = Some("loud"), slowMillis = None, successSampleRate = None)))
        _ <- TestClock.adjust(1.second)
        after <- current.get
        _ <- fiber.interrupt
      yield assertTrue(applied.mode == AccessLogMode.Full, after.mode == AccessLogMode.Full)
    },
  ) @@ TestAspect.timeout(30.seconds) @@ TestAspect.silentLogging

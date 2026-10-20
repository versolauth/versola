package versola.util.http

import zio.*
import zio.json.*
import zio.test.*

object OverloadPolicySpec extends ZIOSpecDefault:

  private val inboundBase = InboundAdmissionSettings(GuardMode.Shadow, 256, 0.85, 0.65, 1)
  private val outboundBase = OutboundGuardSettings(GuardMode.Shadow, 128, 256, 1.second, 10.seconds)

  def spec = suite("OverloadPolicy")(
    test("an empty policy changes nothing") {
      assertTrue(
        OverloadPolicy.empty.applyInbound(inboundBase) == inboundBase,
        OverloadPolicy.empty.applyOutbound(outboundBase) == outboundBase,
        OverloadPolicy.empty.validate.isRight,
      )
    },
    test("a policy is a patch: what it sets replaces the base, the rest stays") {
      val p = OverloadPolicy(
        inbound = Some(InboundPolicy(
          mode = Some("enforce"),
          maxInFlight = Some(100),
          adaptive = Some(AdaptivePolicy(enabled = Some(true), maxLimit = Some(300))),
        )),
        outbound =
          Some(OutboundPolicy(requestTimeoutMillis = Some(2500), breaker = Some(BreakerPolicy(enabled = Some(true), openSeconds = Some(20))))),
      )
      val in = p.applyInbound(inboundBase)
      val out = p.applyOutbound(outboundBase)
      assertTrue(
        in.mode == GuardMode.Enforce,
        in.maxInFlight == 100,
        in.continuationShare == 0.85,
        in.adaptive.enabled,
        in.adaptive.maxLimit == 300,
        in.adaptive.tolerance == AdaptiveSettings.default.tolerance,
        out.mode == GuardMode.Shadow,
        out.requestTimeout == 2500.millis,
        out.maxConcurrent == 128,
        out.breaker.enabled,
        out.breaker.openFor == 20.seconds,
        out.breaker.failureRatio == BreakerSettings.default.failureRatio,
      )
    },
    test("a start share above the continuation share is held down to it") {
      val p = OverloadPolicy(inbound = Some(InboundPolicy(continuationShare = Some(0.5))))
      assertTrue(p.applyInbound(inboundBase).startShare == 0.5)
    },
    test("the json shape: absent fields are None, and it round-trips") {
      val json = """{"inbound":{"mode":"enforce","maxInFlight":128},"outbound":{"breaker":{"enabled":true}}}"""
      val parsed = json.fromJson[OverloadPolicy]
      assertTrue(
        parsed == Right(OverloadPolicy(
          Some(InboundPolicy(mode = Some("enforce"), maxInFlight = Some(128))),
          Some(OutboundPolicy(breaker = Some(BreakerPolicy(enabled = Some(true))))),
        )),
        parsed.map(_.toJson.fromJson[OverloadPolicy]) == Right(parsed),
        "{}".fromJson[OverloadPolicy] == Right(OverloadPolicy.empty),
      )
    },
    suite("validate")(
      test("rejects each kind of nonsense, naming the field") {
        def bad(p: OverloadPolicy) = p.validate.left.getOrElse("")
        assertTrue(
          bad(OverloadPolicy(inbound = Some(InboundPolicy(mode = Some("loud"))))).contains("mode"),
          bad(OverloadPolicy(inbound = Some(InboundPolicy(maxInFlight = Some(0))))).contains("inbound.maxInFlight"),
          bad(OverloadPolicy(inbound = Some(InboundPolicy(startShare = Some(0.9), continuationShare = Some(0.5))))).contains("startShare"),
          bad(OverloadPolicy(inbound = Some(InboundPolicy(retryAfterSeconds = Some(600))))).contains("retryAfterSeconds"),
          bad(OverloadPolicy(inbound =
            Some(InboundPolicy(adaptive = Some(AdaptivePolicy(minLimit = Some(50), maxLimit = Some(10))))),
          )).contains("minLimit"),
          bad(OverloadPolicy(inbound = Some(InboundPolicy(adaptive = Some(AdaptivePolicy(tolerance = Some(0.5))))))).contains("tolerance"),
          bad(OverloadPolicy(outbound = Some(OutboundPolicy(requestTimeoutMillis = Some(0))))).contains("requestTimeoutMillis"),
          bad(OverloadPolicy(outbound =
            Some(OutboundPolicy(breaker = Some(BreakerPolicy(failureRatio = Some(Double.NaN))))),
          )).contains("failureRatio"),
          bad(OverloadPolicy(outbound = Some(OutboundPolicy(retryBudget = Some(RetryBudgetPolicy(ratio = Some(2.0))))))).contains("ratio"),
        )
      },
      test("accepts the documented defaults and the edges of the ranges") {
        val p = OverloadPolicy(
          Some(InboundPolicy(
            Some("enforce"),
            Some(100000),
            Some(1.0),
            Some(0.01),
            Some(60),
            Some(AdaptivePolicy(Some(true), Some(1), Some(100000), Some(5.0), Some(1.0), Some(5))),
          )),
          Some(OutboundPolicy(
            Some("shadow"),
            Some(1),
            Some(1),
            Some(1),
            Some(600000),
            Some(BreakerPolicy(Some(true), Some(1.0), Some(1), Some(300), Some(600), Some(100))),
            Some(RetryBudgetPolicy(Some(0.0), Some(1000.0))),
          )),
        )
        assertTrue(p.validate.isRight)
      },
    ),
  )

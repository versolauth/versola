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
          adaptive = Some(AdaptivePolicy(maxLimit = Some(300), minLimit = None, tolerance = None, smoothing = None, lagTargetMillis = None)),
          continuationShare = None,
          startShare = None,
          retryAfterSeconds = None,
        )),
        outbound =
          Some(OutboundPolicy(requestTimeoutMillis = Some(2500), breaker = Some(BreakerPolicy(enabled = Some(true), openSeconds = Some(20), failureRatio = None, minCalls = None, windowSeconds = None, probeCalls = None)), mode = None, maxConcurrent = None, maxQueue = None, queueTimeoutMillis = None, retryBudget = None)),
      )
      val in = p.applyInbound(inboundBase)
      val out = p.applyOutbound(outboundBase)
      assertTrue(
        in.mode == GuardMode.Enforce,
        in.maxInFlight == 100,
        in.continuationShare == 0.85,
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
      val p = OverloadPolicy(inbound = Some(InboundPolicy(continuationShare = Some(0.5), mode = None, maxInFlight = None, startShare = None, retryAfterSeconds = None, adaptive = None)), outbound = None)
      assertTrue(p.applyInbound(inboundBase).startShare == 0.5)
    },
    test("the json shape: absent fields are None, and it round-trips") {
      val json = """{"inbound":{"mode":"enforce","maxInFlight":128},"outbound":{"breaker":{"enabled":true}}}"""
      val parsed = json.fromJson[OverloadPolicy]
      assertTrue(
        parsed == Right(OverloadPolicy(
          Some(InboundPolicy(mode = Some("enforce"), maxInFlight = Some(128), continuationShare = None, startShare = None, retryAfterSeconds = None, adaptive = None)),
          Some(OutboundPolicy(breaker = Some(BreakerPolicy(enabled = Some(true), failureRatio = None, minCalls = None, windowSeconds = None, openSeconds = None, probeCalls = None)), mode = None, maxConcurrent = None, maxQueue = None, queueTimeoutMillis = None, requestTimeoutMillis = None, retryBudget = None)),
        )),
        parsed.map(_.toJson.fromJson[OverloadPolicy]) == Right(parsed),
        "{}".fromJson[OverloadPolicy] == Right(OverloadPolicy.empty),
      )
    },
    test("a leftover adaptive.enabled is ignored, not an error") {
      val json = """{"inbound":{"mode":"enforce","adaptive":{"enabled":true,"minLimit":8}}}"""
      val decoded = json.fromJson[OverloadPolicy]
      assertTrue(decoded == Right(OverloadPolicy(inbound = Some(InboundPolicy(mode = Some("enforce"), adaptive = Some(AdaptivePolicy(minLimit = Some(8), maxLimit = None, tolerance = None, smoothing = None, lagTargetMillis = None)), maxInFlight = None, continuationShare = None, startShare = None, retryAfterSeconds = None)), outbound = None)))
    },
    suite("validate")(
      test("rejects each kind of nonsense, naming the field") {
        def bad(p: OverloadPolicy) = p.validate.left.getOrElse("")
        assertTrue(
          bad(OverloadPolicy(inbound = Some(InboundPolicy(mode = Some("loud"), maxInFlight = None, continuationShare = None, startShare = None, retryAfterSeconds = None, adaptive = None)), outbound = None)).contains("mode"),
          bad(OverloadPolicy(inbound = Some(InboundPolicy(maxInFlight = Some(0), mode = None, continuationShare = None, startShare = None, retryAfterSeconds = None, adaptive = None)), outbound = None)).contains("inbound.maxInFlight"),
          bad(OverloadPolicy(inbound = Some(InboundPolicy(startShare = Some(0.9), continuationShare = Some(0.5), mode = None, maxInFlight = None, retryAfterSeconds = None, adaptive = None)), outbound = None)).contains("startShare"),
          bad(OverloadPolicy(inbound = Some(InboundPolicy(retryAfterSeconds = Some(600), mode = None, maxInFlight = None, continuationShare = None, startShare = None, adaptive = None)), outbound = None)).contains("retryAfterSeconds"),
          bad(OverloadPolicy(inbound =
            Some(InboundPolicy(adaptive = Some(AdaptivePolicy(minLimit = Some(50), maxLimit = Some(10), tolerance = None, smoothing = None, lagTargetMillis = None)), mode = None, maxInFlight = None, continuationShare = None, startShare = None, retryAfterSeconds = None)),
            outbound = None,
          )).contains("minLimit"),
          bad(OverloadPolicy(inbound = Some(InboundPolicy(adaptive = Some(AdaptivePolicy(tolerance = Some(0.5), minLimit = None, maxLimit = None, smoothing = None, lagTargetMillis = None)), mode = None, maxInFlight = None, continuationShare = None, startShare = None, retryAfterSeconds = None)), outbound = None)).contains("tolerance"),
          bad(OverloadPolicy(outbound = Some(OutboundPolicy(requestTimeoutMillis = Some(0), mode = None, maxConcurrent = None, maxQueue = None, queueTimeoutMillis = None, breaker = None, retryBudget = None)), inbound = None)).contains("requestTimeoutMillis"),
          bad(OverloadPolicy(outbound =
            Some(OutboundPolicy(breaker = Some(BreakerPolicy(failureRatio = Some(Double.NaN), enabled = None, minCalls = None, windowSeconds = None, openSeconds = None, probeCalls = None)), mode = None, maxConcurrent = None, maxQueue = None, queueTimeoutMillis = None, requestTimeoutMillis = None, retryBudget = None)),
            inbound = None,
          )).contains("failureRatio"),
          bad(OverloadPolicy(outbound = Some(OutboundPolicy(retryBudget = Some(RetryBudgetPolicy(ratio = Some(2.0), minPerSecond = None)), mode = None, maxConcurrent = None, maxQueue = None, queueTimeoutMillis = None, requestTimeoutMillis = None, breaker = None)), inbound = None)).contains("ratio"),
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
            Some(AdaptivePolicy(Some(1), Some(100000), Some(5.0), Some(1.0), Some(5))),
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

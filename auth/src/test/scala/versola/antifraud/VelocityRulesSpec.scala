package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

import java.time.Instant

object VelocityRulesSpec extends UnitSpecBase:

  import VelocityRules.Limit

  private val t0 = Instant.parse("2026-10-21T10:00:00Z")

  private def settings(thresholds: VelocityRules.Thresholds) = VelocityCounters.Settings(enabled = true, thresholds = thresholds)

  private val ctx = AntifraudContext(
    tenantId = Some(TenantId("t")),
    clientId = ClientId("c"),
    action = AntifraudAction.otp_request,
    subjectHash = Some("hash"),
    destPrefix = Some("4915"),
    ip = Some("203.0.113.7"),
  )

  /** Makes `n` requests as the evaluate caller does and decides the last one. */
  private def decide(s: VelocityCounters.Settings, n: Int, request: AntifraudContext = ctx): UIO[Decision] =
    val counters = VelocityCounters(s)
    val antifraud = Antifraud.Impl(Antifraud.Settings(enabled = true, blockAt = 50), List(VelocityRules.ruleSet(s)))
    ZIO.foreach(1 to n)(_ => ZIO.succeed(VelocityRules.recordAndSnapshot(counters, s, request, t0))).flatMap(ctxs => antifraud.evaluate(ctxs.last))

  def spec = suite("VelocityRules")(
    test("fires when a limit is exceeded, not when it is reached") {
      val s = settings(VelocityRules.Thresholds(phone = Limit(perTenMinutes = 3)))
      for
        reached <- decide(s, 3)
        over <- decide(s, 4)
      yield assertTrue(reached == Decision.allow, over == Decision(DecisionAction.Block, 50, List("velocity_phone_10m")))
    },
    test("a limit applies to its key and window only, and 0 is no limit") {
      val s = settings(VelocityRules.Thresholds(ip = Limit(perMinute = 2), prefix = Limit(perHour = 5, weight = 10)))
      for
        ip <- decide(s, 3)
        both <- decide(s, 6)
        other <- decide(s, 10, ctx.copy(ip = None, destPrefix = None, subjectHash = None))
      yield assertTrue(
        ip.firedRules == List("velocity_ip_1m"),
        both.firedRules == List("velocity_ip_1m", "velocity_prefix_1h"),
        both.score == 60,
        other == Decision.allow,
      )
    },
    test("ranges are counted per tenant") {
      val s = settings(VelocityRules.Thresholds(prefix = Limit(perHour = 2)))
      val counters = VelocityCounters(s)
      List("t", "t", "u").foreach(t => VelocityRules.recordAndSnapshot(counters, s, ctx.copy(tenantId = Some(TenantId(t))), t0))
      val last = VelocityRules.recordAndSnapshot(counters, s, ctx.copy(tenantId = Some(TenantId("u"))), t0)
      assertTrue(last.signals == Map("velocity.prefix.1h" -> "2"))
    },
    test("carries the counts in the signals, only of the windows with a limit, and leaves other signals alone") {
      val s = settings(VelocityRules.Thresholds(ip = Limit(perMinute = 1, perDay = 9)))
      val out = VelocityRules.recordAndSnapshot(VelocityCounters(s), s, ctx.copy(signals = Map("x" -> "y")), t0)
      assertTrue(out.signals == Map("x" -> "y", "velocity.ip.1m" -> "1", "velocity.ip.24h" -> "1"))
    },
    test("has no rules while the counters are off, whatever the limits") {
      val s = settings(VelocityRules.Thresholds(ip = Limit(perMinute = 1))).copy(enabled = false)
      VelocityRules.ruleSet(s).rules.map(rules => assertTrue(rules.isEmpty))
    },
  )

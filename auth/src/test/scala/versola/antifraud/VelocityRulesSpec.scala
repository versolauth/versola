package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

object VelocityRulesSpec extends UnitSpecBase:

  import VelocityRules.Limit

  private def settings(thresholds: VelocityRules.Thresholds) = AntifraudTestSettings.velocity.copy(enabled = true, thresholds = thresholds)

  private val ctx = AntifraudContext(
    tenantId = Some(TenantId("t")),
    clientId = ClientId("c"),
    action = AntifraudAction.otp_request,
    subjectHash = Some("hash"),
    destPrefix = Some("4915"),
    deviceId = None,
    ip = Some("203.0.113.7"),
    asn = None,
    userAgent = None,
    ja4 = None,
    signals = Map.empty,
  )

  private def antifraud(s: VelocityCounters.Settings, blockAt: Int = 50) =
    Antifraud.Impl(AntifraudTestSettings.decision.copy(enabled = true, blockAt = blockAt), List(VelocityRules.ruleSet(s)))

  /** Makes `n` requests as a caller that counts every step does, and decides the last one. */
  private def decide(s: VelocityCounters.Settings, n: Int, request: AntifraudContext = ctx, blockAt: Int = 50): UIO[Decision] =
    val counters = VelocityCounters(s)
    ZIO.foreach(1 to n)(_ => counters.recordAndSnapshot(request)).flatMap(ctxs => antifraud(s, blockAt).evaluate(ctxs.last))

  def spec = suite("VelocityRules")(
    test("fires when a limit is exceeded, not when it is reached") {
      val s = settings(AntifraudTestSettings.thresholds.copy(phone = AntifraudTestSettings.limit.copy(perTenMinutes = 3, weight = 50)))
      for
        reached <- decide(s, 3)
        over <- decide(s, 4)
      yield assertTrue(reached == Decision.allow, over == Decision(DecisionAction.Block, 50, List("velocity_phone_10m")))
    },
    test("a limit applies to its key and window only, and 0 is no limit") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perMinute = 2, weight = 50), prefix = AntifraudTestSettings.limit.copy(perHour = 5, weight = 10)))
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
    test("ranges are counted per tenant, the phone is not") {
      val s = settings(AntifraudTestSettings.thresholds.copy(prefix = AntifraudTestSettings.limit.copy(perHour = 2), phone = AntifraudTestSettings.limit.copy(perHour = 2)))
      val counters = VelocityCounters(s)
      for
        _ <- ZIO.foreachDiscard(List("t", "t", "u"))(t => counters.recordAndSnapshot(ctx.copy(tenantId = Some(TenantId(t)))))
        last <- counters.recordAndSnapshot(ctx.copy(tenantId = Some(TenantId("u"))))
      yield assertTrue(last.signals("velocity.prefix.1h") == "2", last.signals("velocity.phone.1h") == "4")
    },
    test("carries the counts in the signals, only of the windows with a limit, and leaves other signals alone") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perMinute = 1, perDay = 9)))
      VelocityCounters(s).recordAndSnapshot(ctx.copy(signals = Map("x" -> "y"))).map: out =>
        assertTrue(out.signals == Map("x" -> "y", "velocity.ip.1m" -> "1", "velocity.ip.24h" -> "1"))
    },
    test("a snapshot counts the request as if it were recorded, without recording it, so a refused step is not counted") {
      val s = settings(AntifraudTestSettings.thresholds.copy(phone = AntifraudTestSettings.limit.copy(perHour = 2, weight = 50)))
      val counters = VelocityCounters(s)
      for
        first <- counters.snapshot(ctx)
        again <- counters.snapshot(ctx)
        _ <- counters.record(ctx)
        _ <- counters.record(ctx)
        third <- counters.snapshot(ctx)
        decision <- antifraud(s).evaluate(third)
      yield assertTrue(
        first.signals("velocity.phone.1h") == "1",
        again.signals("velocity.phone.1h") == "1",
        third.signals("velocity.phone.1h") == "3",
        decision.action == DecisionAction.Block,
      )
    },
    test("a known user is let off part of the weight, and only when a velocity rule fired") {
      val s = settings(AntifraudTestSettings.thresholds.copy(phone = AntifraudTestSettings.limit.copy(perHour = 2, weight = 50), trustedDiscount = 40))
      val counters = VelocityCounters(s)
      val known = ctx.copy(signals = Map("device.known" -> "true"))
      val recent = ctx.copy(signals = Map("session.recent_success" -> "true"))
      val denied = ctx.copy(signals = Map("device.known" -> "false"))
      for
        _ <- ZIO.foreachDiscard(1 to 2)(_ => counters.record(ctx))
        a <- counters.snapshot(known).flatMap(antifraud(s, blockAt = 50).evaluate)
        b <- counters.snapshot(recent).flatMap(antifraud(s, blockAt = 50).evaluate)
        c <- counters.snapshot(denied).flatMap(antifraud(s, blockAt = 50).evaluate)
        quiet <- VelocityCounters(s).snapshot(known).flatMap(antifraud(s).evaluate)
      yield assertTrue(
        a == Decision(DecisionAction.Allow, 10, List("velocity_phone_1h", "velocity_known_user")),
        b.score == 10,
        c.action == DecisionAction.Block,
        quiet == Decision.allow,
      )
    },
    test("an address ip is weighted below a device by default, for the many users behind one NAT") {
      val t = AntifraudTestSettings.thresholds
      assertTrue(t.ip.weight < t.device.weight, t.asn.weight < t.device.weight, t.ip.weight < t.phone.weight)
    },
    test("names the network of an address: spellings alike, IPv4-mapped is IPv4, IPv6 by its /64, anything else is no key") {
      import VelocityRules.network
      assertTrue(
        network("203.0.113.7") == Some("4:203.0.113.7"),
        network("::ffff:203.0.113.7") == Some("4:203.0.113.7"),
        network("::1") == network("0:0:0:0:0:0:0:1"),
        network("2001:db8:0:1::1") == network("2001:0DB8:0000:0001:aaaa:bbbb:cccc:dddd"),
        network("2001:db8:0:1::1") != network("2001:db8:0:2::1"),
        network("www.example.com").isEmpty,
        network("").isEmpty,
        network("1.2.3").isEmpty,
        network("1.2.3.4, 5.6.7.8").isEmpty,
        network("fe80::1%eth0").isEmpty,
      )
    },
    test("has no rules while the counters are off, whatever the limits") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perMinute = 1))).copy(enabled = false)
      VelocityRules.ruleSet(s).rules.map(rules => assertTrue(rules.isEmpty))
    },
  )

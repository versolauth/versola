package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

object VelocityRulesSpec extends UnitSpecBase:

  import VelocityRules.Limit

  /** The counters' settings and the tuning with the limits. */
  private case class Setup(settings: VelocityCounters.Settings, tuning: Tuning)

  private def settings(thresholds: VelocityRules.Thresholds) =
    Setup(AntifraudTestSettings.velocity.copy(enabled = true), AntifraudTestSettings.tuning.copy(thresholds = thresholds))

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

  private def antifraud(s: Setup, blockAt: Int = 50, share: CellShare = CellShare.whole) =
    AntifraudTestSettings.engine(
      AntifraudTestSettings.decision.copy(enabled = true),
      List(AntifraudTestSettings.velocityRules(s.settings, s.tuning, share)),
      s.tuning.copy(blockAt = blockAt),
    )

  /** Makes `n` requests as a caller that counts every step does, and decides the last one. */
  private def decide(
      s: Setup,
      n: Int,
      request: AntifraudContext = ctx,
      blockAt: Int = 50,
      share: CellShare = CellShare.whole,
  ): UIO[Decision] =
    val counters = VelocityCounters(s.settings, AntifraudTestSettings.velocityLimits, s.tuning.thresholds)
    ZIO.foreach(1 to n)(_ => counters.recordAndSnapshot(request)).flatMap(ctxs => antifraud(s, blockAt, share).evaluate(ctxs.last))

  private def sharing(numerator: Long, denominator: Long): CellShare = new CellShare:
    override def current: UIO[CellShare.Share] = ZIO.succeed(CellShare.Share(numerator, denominator))

  def spec = suite("VelocityRules")(
    test("fires when a limit is exceeded, not when it is reached") {
      val s = settings(AntifraudTestSettings.thresholds.copy(phone = AntifraudTestSettings.limit.copy(perTenMinutes = 3, weight = 50)))
      for
        reached <- decide(s, 3)
        over <- decide(s, 4)
      yield assertTrue(reached == Decision.allow, over == Decision(DecisionAction.Block, 50, List("velocity_phone_10m")))
    },
    // versolauth/versola#1052: a limit by address holds for the deployment, so a cell applies its share of it.
    test("a cell fires the limits of the keys that are not confined to it above ceil(limit * share), and not before") {
      val limit = AntifraudTestSettings.limit.copy(perHour = 10, weight = 50)
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = limit, asn = limit, prefix = limit))
      val third = sharing(1, 3)
      def fires(request: AntifraudContext, rule: String)(n: Int) =
        decide(s, n, request, blockAt = 100, share = third).map(_.firedRules.contains(rule))
      val byIp = ctx.copy(subjectHash = None, destPrefix = None)
      val byAsn = ctx.copy(subjectHash = None, destPrefix = None, ip = None, asn = Some(64500))
      val byPrefix = ctx.copy(subjectHash = None, ip = None)
      for
        // 10 * 1/3 = 3.33..., so 4 is allowed (a limit is reached, not exceeded) and the fifth request fires
        ipAt4 <- fires(byIp, "velocity_ip_1h")(4)
        ipAt5 <- fires(byIp, "velocity_ip_1h")(5)
        asnAt4 <- fires(byAsn, "velocity_asn_1h")(4)
        asnAt5 <- fires(byAsn, "velocity_asn_1h")(5)
        prefixAt4 <- fires(byPrefix, "velocity_prefix_1h")(4)
        prefixAt5 <- fires(byPrefix, "velocity_prefix_1h")(5)
      yield assertTrue(!ipAt4, ipAt5, !asnAt4, asnAt5, !prefixAt4, prefixAt5)
    },
    test("a limit of 100 lets 34 through and fires above it in a cell with a share of 34 hundredths") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perDay = 100)))
      val byIp = ctx.copy(subjectHash = None, destPrefix = None)
      for
        at34 <- decide(s, 34, byIp, share = sharing(34, 100))
        at35 <- decide(s, 35, byIp, share = sharing(34, 100))
      yield assertTrue(at34 == Decision.allow, at35.firedRules == List("velocity_ip_24h"))
    },
    test("a phone is counted against its whole limit in a cell that has a share of the rest") {
      val s = settings(AntifraudTestSettings.thresholds.copy(phone = AntifraudTestSettings.limit.copy(perHour = 10, weight = 50)))
      val byPhone = ctx.copy(ip = None, destPrefix = None)
      for
        // 1/3 of 10 would fire above 4
        atFive <- decide(s, 5, byPhone, share = sharing(1, 3))
        atTen <- decide(s, 10, byPhone, share = sharing(1, 3))
        atEleven <- decide(s, 11, byPhone, share = sharing(1, 3))
      yield assertTrue(atFive == Decision.allow, atTen == Decision.allow, atEleven.firedRules == List("velocity_phone_1h"))
    },
    test("a device is counted against its whole limit too") {
      val s = settings(AntifraudTestSettings.thresholds.copy(device = AntifraudTestSettings.limit.copy(perHour = 10, weight = 50)))
      val byDevice = ctx.copy(ip = None, destPrefix = None, subjectHash = None, deviceId = Some("dev"))
      for
        atFive <- decide(s, 5, byDevice, share = sharing(1, 3))
        atEleven <- decide(s, 11, byDevice, share = sharing(1, 3))
      yield assertTrue(atFive == Decision.allow, atEleven.firedRules == List("velocity_device_1h"))
    },
    test("a limit of 0 stays no limit whatever the share, and a share that would round a limit to 0 gives 1") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perMinute = 0, perHour = 1, weight = 50)))
      val byIp = ctx.copy(subjectHash = None, destPrefix = None)
      for
        one <- decide(s, 1, byIp, share = sharing(1, 100))
        two <- decide(s, 2, byIp, share = sharing(1, 100))
        many <- decide(s, 20, byIp, share = sharing(1, 100))
      yield assertTrue(
        one == Decision.allow,
        two.firedRules == List("velocity_ip_1h"),
        // No rule is made for the window that has no limit.
        !many.firedRules.contains("velocity_ip_1m"),
      )
    },
    test("the rules carry the share: the same limits give the whole limit where the cell has the whole deployment") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perHour = 10, weight = 50)))
      val byIp = ctx.copy(subjectHash = None, destPrefix = None)
      for
        atFour <- decide(s, 4, byIp)
        atEleven <- decide(s, 11, byIp)
      yield assertTrue(atFour == Decision.allow, atEleven.firedRules == List("velocity_ip_1h"))
    },
    test("a change of the share applies to the next request, with no restart") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perHour = 10, weight = 50)))
      val byIp = ctx.copy(subjectHash = None, destPrefix = None)
      for
        share <- Ref.make(CellShare.Share(1, 1))
        changing = new CellShare:
          override def current: UIO[CellShare.Share] = share.get
        before <- decide(s, 5, byIp, share = changing)
        _ <- share.set(CellShare.Share(1, 3))
        after <- decide(s, 5, byIp, share = changing)
      yield assertTrue(before == Decision.allow, after.firedRules == List("velocity_ip_1h"))
    },
    test("a limit applies to its key and window only, and 0 is no limit") {
      val s = settings(AntifraudTestSettings.thresholds.copy(
        ip = AntifraudTestSettings.limit.copy(perMinute = 2, weight = 50),
        prefix = AntifraudTestSettings.limit.copy(perHour = 5, weight = 10),
      ))
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
      val s = settings(AntifraudTestSettings.thresholds.copy(
        prefix = AntifraudTestSettings.limit.copy(perHour = 2),
        phone = AntifraudTestSettings.limit.copy(perHour = 2),
      ))
      val counters = VelocityCounters(s.settings, AntifraudTestSettings.velocityLimits, s.tuning.thresholds)
      for
        _ <- ZIO.foreachDiscard(List("t", "t", "u"))(t => counters.recordAndSnapshot(ctx.copy(tenantId = Some(TenantId(t)))))
        last <- counters.recordAndSnapshot(ctx.copy(tenantId = Some(TenantId("u"))))
      yield assertTrue(last.signals("velocity.prefix.1h") == "2", last.signals("velocity.phone.1h") == "4")
    },
    test("carries the counts in the signals, only of the windows with a limit, and leaves other signals alone") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perMinute = 1, perDay = 9)))
      VelocityCounters(
        s.settings,
        AntifraudTestSettings.velocityLimits,
        s.tuning.thresholds,
      ).recordAndSnapshot(ctx.copy(signals = Map("x" -> "y"))).map: out =>
        assertTrue(out.signals == Map("x" -> "y", "velocity.ip.1m" -> "1", "velocity.ip.24h" -> "1"))
    },
    test("a snapshot counts the request as if it were recorded, without recording it, so a refused step is not counted") {
      val s = settings(AntifraudTestSettings.thresholds.copy(phone = AntifraudTestSettings.limit.copy(perHour = 2, weight = 50)))
      val counters = VelocityCounters(s.settings, AntifraudTestSettings.velocityLimits, s.tuning.thresholds)
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
    test(
      "a snapshot of a request that is already recorded does not add it a second time, except for the phone, which is counted when a code is sent",
    ) {
      val s = settings(AntifraudTestSettings.thresholds.copy(
        ip = AntifraudTestSettings.limit.copy(perHour = 3, weight = 60),
        phone = AntifraudTestSettings.limit.copy(perHour = 3, weight = 60),
      ))
      val counters = VelocityCounters(s.settings, AntifraudTestSettings.velocityLimits, s.tuning.thresholds)
      // The source keys only, as `decideOtpRequest` records them; the phone is not recorded with the decision.
      val source = ctx.copy(subjectHash = None)
      for
        _ <- ZIO.foreachDiscard(1 to 2)(_ => counters.record(source))
        atMargin <- counters.snapshot(ctx)
        _ <- counters.record(source)
        asNew <- counters.snapshot(ctx)
        asRepeated <- counters.snapshot(ctx, recorded = true)
        newDecision <- antifraud(s).evaluate(asNew)
        repeatedDecision <- antifraud(s).evaluate(asRepeated)
      yield assertTrue(
        // two recorded: a new request is the 3rd, at the limit
        atMargin.signals("velocity.ip.1h") == "3",
        // three recorded, this one among them: a new request would be the 4th, the repeated one is the 3rd
        asNew.signals("velocity.ip.1h") == "4",
        asRepeated.signals("velocity.ip.1h") == "3",
        // the phone is not recorded yet in either, and the code about to be sent is the one added
        asNew.signals("velocity.phone.1h") == "1",
        asRepeated.signals("velocity.phone.1h") == "1",
        newDecision.firedRules == List("velocity_ip_1h"),
        repeatedDecision == Decision.allow,
      )
    },
    test("a known user is let off part of the weight, and only when a velocity rule fired") {
      val s =
        settings(AntifraudTestSettings.thresholds.copy(phone = AntifraudTestSettings.limit.copy(perHour = 2, weight = 50), trustedDiscount = 40))
      val counters = VelocityCounters(s.settings, AntifraudTestSettings.velocityLimits, s.tuning.thresholds)
      val known = ctx.copy(signals = Map("device.known" -> "true"))
      val recent = ctx.copy(signals = Map("session.recent_success" -> "true"))
      val denied = ctx.copy(signals = Map("device.known" -> "false"))
      for
        _ <- ZIO.foreachDiscard(1 to 2)(_ => counters.record(ctx))
        a <- counters.snapshot(known).flatMap(antifraud(s, blockAt = 50).evaluate)
        b <- counters.snapshot(recent).flatMap(antifraud(s, blockAt = 50).evaluate)
        c <- counters.snapshot(denied).flatMap(antifraud(s, blockAt = 50).evaluate)
        quiet <-
          VelocityCounters(s.settings, AntifraudTestSettings.velocityLimits, s.tuning.thresholds).snapshot(known).flatMap(antifraud(s).evaluate)
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
        // Not literals: InetAddress would have looked these up as host names.
        network("1.2.3.456").isEmpty,
        network("ab.cd.ef.ab").isEmpty,
        network("dead.beef.cafe.de").isEmpty,
      )
    },
    test("has no rules while the counters are off, whatever the limits") {
      val s = settings(AntifraudTestSettings.thresholds.copy(ip = AntifraudTestSettings.limit.copy(perMinute = 1)))
      AntifraudTestSettings.velocityRules(s.settings.copy(enabled = false), s.tuning).rules.map(rules => assertTrue(rules.isEmpty))
    },
  )

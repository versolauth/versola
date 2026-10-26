package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

import java.time.Instant

object AntifraudTuningSpec extends UnitSpecBase:

  private val tenant = TenantId("tenant-a")
  private val other = TenantId("tenant-b")
  private val at = Instant.parse("2026-10-26T10:00:00Z")
  private val base = AntifraudTuning.fromSettings(AntifraudTestSettings.events)

  private def patch(
      delayAt: Option[Int] = None,
      blockAt: Option[Int] = None,
      delayMillis: Option[Long] = None,
      thresholds: Option[VelocityRules.Thresholds] = None,
      smsLimits: Option[SmsLimits] = None,
      ruleWeights: Option[Map[String, Int]] = None,
      shadowRules: Option[Set[String]] = None,
  ) =
    TuningPatch(
      delayAt = delayAt,
      challengeAt = None,
      reviewAt = None,
      blockAt = blockAt,
      delayMillis = delayMillis,
      fakeSendDelayMillis = None,
      thresholds = thresholds,
      smsLimits = smsLimits,
      ruleWeights = ruleWeights,
      shadowRules = shadowRules,
    )

  /** The repository in memory, with a version that moves with every change. */
  private class InMemory(rows: Ref[Map[Option[TenantId], TuningPatch]], counter: Ref[Long], failing: Ref[Boolean]) extends AntifraudSettingsRepository:
    override def put(tenant: Option[TenantId], patch: TuningPatch, actor: String, at: Instant) =
      rows.update(_ + (tenant -> patch)) *> counter.update(_ + 1)

    override def delete(tenant: Option[TenantId], actor: String, at: Instant) =
      rows.modify(r => (r.contains(tenant), r - tenant)).tap(removed => counter.update(_ + 1).when(removed))

    override def find(tenant: Option[TenantId]) = rows.get.map(_.get(tenant))

    override def version: Task[Long] = failing.get.flatMap(f => if f then ZIO.fail(RuntimeException("down")) else counter.get)

    override def loadAll = rows.get.map(_.toList)

    override def audit(scope: Option[Option[TenantId]], limit: Int) = ZIO.succeed(Nil)

  private val tuningLayer: UIO[(AntifraudTuning.Impl, Ref[Boolean], InMemory)] =
    for
      rows <- Ref.make(Map.empty[Option[TenantId], TuningPatch])
      version <- Ref.make(0L)
      failing <- Ref.make(false)
      repository = InMemory(rows, version, failing)
    yield (AntifraudTuning.Impl(repository, base), failing, repository)

  private val ctx = AntifraudContext(Some(tenant), ClientId("c"), AntifraudAction.otp_request, None, None, None, None, None, None, None, Map.empty)

  private def rule(name: String, weight: Int, forces: Option[DecisionAction] = None) = AntifraudRule(name, weight, forces, _ => true)

  private def rules(rs: AntifraudRule*): Antifraud.RuleSet = new Antifraud.RuleSet:
    def rules = ZIO.succeed(rs.toList)

  private val enabled = AntifraudTestSettings.decision.copy(enabled = true)

  def spec = suite("AntifraudTuning")(
    test("where nothing is stored the config applies") {
      for (tuning, _, _) <- tuningLayer
      yield assertTrue(tuning.effective(None) == base, tuning.effective(Some(tenant)) == base, !tuning.smsLimitsEnabled)
    },
    test("the global row is laid over the config, a tenant's over that, and a key a row leaves out is the layer below's") {
      for
        (tuning, _, _) <- tuningLayer
        _ <- tuning.put(None, patch(delayAt = Some(10), blockAt = Some(100)), "alice")
        _ <- tuning.put(Some(tenant), patch(blockAt = Some(50)), "alice")
      yield assertTrue(
        tuning.effective(None).blockAt == 100,
        tuning.effective(Some(other)).blockAt == 100,
        tuning.effective(Some(tenant)).blockAt == 50,
        tuning.effective(Some(tenant)).delayAt == 10,
        tuning.effective(Some(tenant)).challengeAt == base.challengeAt,
      )
    },
    test("deleting a row returns the scope to the layer below") {
      for
        (tuning, _, _) <- tuningLayer
        _ <- tuning.put(Some(tenant), patch(blockAt = Some(50)), "alice")
        removed <- tuning.delete(Some(tenant), "alice")
        again <- tuning.delete(Some(tenant), "alice")
      yield assertTrue(removed, !again, tuning.effective(Some(tenant)) == base)
    },
    test("another instance picks a change up on its next refresh") {
      for
        (writer, _, repository) <- tuningLayer
        reader = AntifraudTuning.Impl(repository, base)
        _ <- writer.put(None, patch(blockAt = Some(70)), "alice")
        before = reader.effective(None).blockAt
        _ <- reader.refresh
        loaded = reader.effective(None).blockAt
      yield assertTrue(before == base.blockAt, loaded == 70)
    },
    test("a refresh that fails keeps the tuning there is") {
      for
        (tuning, failing, _) <- tuningLayer
        _ <- tuning.put(None, patch(blockAt = Some(70)), "alice")
        _ <- failing.set(true)
        _ <- tuning.refresh
      yield assertTrue(tuning.effective(None).blockAt == 70)
    },
    test("refuses what cannot be meant") {
      def refused(p: TuningPatch, tenant: Boolean = false) = AntifraudTuning.invalid(p, tenant).isDefined
      assertTrue(
        refused(patch(blockAt = Some(-1))),
        refused(patch(delayMillis = Some(600000))),
        refused(patch(delayMillis = Some(100)), tenant = true),
        !refused(patch(delayMillis = Some(100))),
        refused(patch(smsLimits = Some(SmsLimits(SmsLimit(0, 0, 0.1), Map("49x" -> SmsLimit(5, 0, 0.1)))))),
        refused(patch(smsLimits = Some(SmsLimits(SmsLimit(0, 0, 2.0), Map.empty)))),
        !refused(patch(smsLimits = Some(SmsLimits(SmsLimit(0, 0, 0.1), Map("49" -> SmsLimit(5, 0, 0.1)))))),
        refused(patch(ruleWeights = Some(Map("Bad Name" -> 5)))),
        refused(patch(shadowRules = Some(Set("")))),
      )
    },
    test("put does not store what is refused") {
      for
        (tuning, _, _) <- tuningLayer
        result <- tuning.put(None, patch(blockAt = Some(-5)), "alice").either
        stored <- tuning.stored(None)
      yield assertTrue(result.left.exists(_.isInstanceOf[InvalidTuning]), stored.isEmpty)
    },
    test("the engine takes the thresholds of the request's tenant") {
      for
        (tuning, _, _) <- tuningLayer
        _ <- tuning.put(None, patch(blockAt = Some(100)), "alice")
        _ <- tuning.put(Some(tenant), patch(blockAt = Some(5)), "alice")
        engine = Antifraud.Impl(enabled, List(rules(rule("a", 6))), tuning)
        tenantDecision <- engine.evaluate(ctx)
        otherDecision <- engine.evaluate(ctx.copy(tenantId = Some(other)))
      yield assertTrue(tenantDecision.action == DecisionAction.Block, otherDecision.action == DecisionAction.Allow)
    },
    test("a rule weight overrides the rule's, but not a rule that forces an action") {
      for
        (tuning, _, _) <- tuningLayer
        _ <- tuning.put(None, patch(blockAt = Some(10), ruleWeights = Some(Map("a" -> 20, "deny" -> 1))), "alice")
        engine = Antifraud.Impl(enabled, List(rules(rule("a", 1))), tuning)
        heavy <- engine.evaluate(ctx)
        forced <- Antifraud.Impl(enabled, List(rules(rule("deny", 0, Some(DecisionAction.Fake)))), tuning).evaluate(ctx)
      yield assertTrue(heavy.score == 20, heavy.action == DecisionAction.Block, forced.score == 0, forced.action == DecisionAction.Fake)
    },
    test("a shadow rule is listed as such and adds nothing") {
      for
        (tuning, _, _) <- tuningLayer
        _ <- tuning.put(None, patch(blockAt = Some(5), shadowRules = Some(Set("a", "deny"))), "alice")
        engine = Antifraud.Impl(enabled, List(rules(rule("a", 9), rule("b", 1), rule("deny", 0, Some(DecisionAction.Block)))), tuning)
        decision <- engine.evaluate(ctx)
      yield assertTrue(decision.score == 1, decision.action == DecisionAction.Allow, decision.firedRules == List("b", "shadow:a", "shadow:deny"))
    },
    test("the limit on codes sent follows the longest prefix a number starts with, and a tenant's own") {
      val limits = SmsLimits(SmsLimit(0, 0, 0.1), Map("49" -> SmsLimit(2, 0, 0.1), "4915" -> SmsLimit(1, 0, 0.1)))
      for
        (tuning, _, _) <- tuningLayer
        _ <- tuning.put(Some(tenant), patch(smsLimits = Some(limits)), "alice")
        budget = new SmsBudget(AntifraudTestSettings.smsBudget.copy(prefixLength = 6), t => tuning.effective(t).smsLimits)
        first = budget.decide(Some(tenant), "491512", at, charge = true)
        second = budget.decide(Some(tenant), "491512", at, charge = true)
        german = (budget.decide(Some(tenant), "491612", at, charge = true), budget.decide(Some(tenant), "491612", at, charge = true), budget.decide(Some(tenant), "491612", at, charge = true))
        unlimited = (1 to 5).map(_ => budget.decide(Some(other), "491512", at, charge = true)).toSet
        elsewhere = (1 to 5).map(_ => budget.decide(Some(tenant), "771012", at, charge = true)).toSet
      yield assertTrue(
        tuning.smsLimitsEnabled,
        first == SmsBudget.Decision.Allowed,
        second == SmsBudget.Decision.Refused,
        german == (SmsBudget.Decision.Allowed, SmsBudget.Decision.Allowed, SmsBudget.Decision.Refused),
        unlimited == Set(SmsBudget.Decision.Allowed),
        elsewhere == Set(SmsBudget.Decision.Allowed),
      )
    },
    test("the velocity limits are the tenant's") {
      val limit = AntifraudTestSettings.limit.copy(perHour = 2)
      for
        (tuning, _, _) <- tuningLayer
        _ <- tuning.put(Some(tenant), patch(thresholds = Some(base.thresholds.copy(phone = limit))), "alice")
        settings = AntifraudTestSettings.velocity.copy(enabled = true)
        ruleSet = VelocityRules.ruleSet(settings, tuning)
        forTenant <- ruleSet.rulesFor(ctx)
        forOther <- ruleSet.rulesFor(ctx.copy(tenantId = Some(other)))
      yield assertTrue(forTenant.map(_.name).contains("velocity_phone_1h"), forOther.isEmpty)
    },
  )

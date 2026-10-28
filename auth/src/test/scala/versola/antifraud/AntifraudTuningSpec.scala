package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.UnitSpecBase
import zio.*
import zio.json.*
import zio.test.*

import java.time.Instant

object AntifraudTuningSpec extends UnitSpecBase:

  private val at = Instant.parse("2026-10-26T10:00:00Z")
  private val base = Tuning.off

  /** The repository in memory, with a version that moves with every change; `row` is the seeded one. */
  private class InMemory(row: Ref[Option[Tuning]], counter: Ref[Long], failing: Ref[Boolean]) extends AntifraudSettingsRepository:
    override def put(tuning: Tuning, actor: String, at: Instant) = row.set(Some(tuning)) *> counter.update(_ + 1)

    override def seed(tuning: Tuning, actor: String, at: Instant) =
      row.modify(r => (r.isEmpty, r.orElse(Some(tuning)))).tap(wrote => counter.update(_ + 1).when(wrote))

    override def find = row.get

    override def version: Task[Long] = failing.get.flatMap(f => if f then ZIO.fail(RuntimeException("down")) else counter.get)

  private def repository(seeded: Boolean, down: Boolean = false) =
    for
      row <- Ref.make(Option.when(seeded)(Tuning.off))
      counter <- Ref.make(0L)
      failing <- Ref.make(down)
    yield (InMemory(row, counter, failing), failing)

  /** The store over a repository that has the seeded row, read once. */
  private val storeLayer: UIO[(AntifraudTuningStore.Impl, Ref[Boolean], InMemory)] =
    for
      (repo, failing) <- repository(seeded = true)
      store = AntifraudTuningStore.Impl(repo)
      _ <- store.refresh
    yield (store, failing, repo)

  private val ctx = AntifraudContext(Some(TenantId("a")), ClientId("c"), AntifraudAction.otp_request, None, None, None, None, None, None, None, Map.empty)

  private def rule(name: String, weight: Int, forces: Option[DecisionAction] = None) = AntifraudRule(name, weight, forces, _ => true)

  private def rules(rs: AntifraudRule*): Antifraud.RuleSet = new Antifraud.RuleSet:
    def rules = ZIO.succeed(rs.toList)

  private val enabled = AntifraudTestSettings.decision.copy(enabled = true)

  def spec = suite("AntifraudTuning")(
    test("the seeded row applies, and everything is off") {
      for (store, _, _) <- storeLayer
      yield assertTrue(store.available, store.current == base, !store.current.smsLimits.enabled)
    },
    test("before the first read nothing is taken to be the thresholds: a request gets the failure action, and the tuning is off") {
      for
        (repo, failing) <- repository(seeded = true, down = true)
        store = AntifraudTuningStore.Impl(repo)
        _ <- store.refresh
        before = store.available
        engine = Antifraud.Impl(enabled.copy(failureAction = DecisionAction.Challenge), List(rules(rule("a", 1))), store)
        cold <- engine.evaluate(ctx)
        _ <- failing.set(false)
        _ <- store.refresh
        warm <- engine.evaluate(ctx)
      yield assertTrue(
        !before,
        cold == Decision(DecisionAction.Challenge, 0, List("antifraud_tuning_unavailable")),
        store.available,
        warm == Decision(DecisionAction.Allow, 1, List("a")),
      )
    },
    test("a change applies on the instance that took it at once, and on another on its next refresh") {
      for
        (writer, _, repo) <- storeLayer
        reader = AntifraudTuningStore.Impl(repo)
        _ <- writer.put(base.copy(blockAt = 70), "alice")
        before = reader.current.blockAt
        _ <- reader.refresh
      yield assertTrue(writer.current.blockAt == 70, before == base.blockAt, reader.current.blockAt == 70)
    },
    test("a refresh that fails keeps the tuning there is") {
      for
        (store, failing, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 70), "alice")
        _ <- failing.set(true)
        _ <- store.refresh
      yield assertTrue(store.current.blockAt == 70, store.available)
    },
    test("no row yet is everything off, and the engine runs on it") {
      for
        (repo, _) <- repository(seeded = false)
        store = AntifraudTuningStore.Impl(repo)
        _ <- store.refresh
        decision <- Antifraud.Impl(enabled.copy(failureAction = DecisionAction.Challenge), List(rules(rule("a", 1))), store).evaluate(ctx)
      yield assertTrue(store.available, store.current == base, decision == Decision(DecisionAction.Allow, 1, List("a")))
    },
    test("reset puts the seeded values back") {
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 70, shadowRules = Set("a")), "alice")
        _ <- store.reset("alice")
      yield assertTrue(store.current == base)
    },
    test("a row stored while there was a Delay step or a fake-send wait still reads: those keys are ignored") {
      val stored = base.toJson.dropRight(1) + ""","delayAt":3,"delayMillis":2000,"fakeSendDelayMillis":500}"""
      assertTrue(stored.fromJson[Tuning] == Right(base))
    },
    test("refuses what cannot be meant") {
      def refused(t: Tuning) = AntifraudTuningStore.invalid(t).isDefined
      assertTrue(
        refused(base.copy(blockAt = -1)),
        refused(base.copy(challengeAt = 1000000)),
        refused(base.copy(smsLimits = SmsLimits(SmsLimit(0, 0, 0.1), Map("49x" -> SmsLimit(5, 0, 0.1))))),
        refused(base.copy(smsLimits = SmsLimits(SmsLimit(0, 0, 2.0), Map.empty))),
        !refused(base.copy(smsLimits = SmsLimits(SmsLimit(0, 0, 0.1), Map("49" -> SmsLimit(5, 0, 0.1))))),
        refused(base.copy(ruleWeights = Map("Bad Name" -> 5))),
        refused(base.copy(shadowRules = Set(""))),
        refused(base.copy(pow = PowDifficulty(minDifficulty = 20, maxDifficulty = 18, scoreStep = 15))),
        refused(base.copy(pow = PowDifficulty(minDifficulty = 14, maxDifficulty = 99, scoreStep = 15))),
        !refused(base.copy(pow = PowDifficulty(minDifficulty = 10, maxDifficulty = 20, scoreStep = 0))),
        !refused(base),
      )
    },
    test("put does not store what is refused") {
      for
        (store, _, _) <- storeLayer
        result <- store.put(base.copy(blockAt = -5), "alice").either
        stored <- store.stored
      yield assertTrue(result.left.exists(_.isInstanceOf[InvalidTuning]), stored.contains(base))
    },
    test("the engine takes the thresholds that are stored") {
      for
        (store, _, _) <- storeLayer
        engine = Antifraud.Impl(enabled, List(rules(rule("a", 6))), store)
        before <- engine.evaluate(ctx)
        _ <- store.put(base.copy(blockAt = 5), "alice")
        after <- engine.evaluate(ctx)
      yield assertTrue(before.action == DecisionAction.Allow, after.action == DecisionAction.Block)
    },
    test("a rule weight overrides the rule's, but not a rule that forces an action") {
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 10, ruleWeights = Map("a" -> 20, "deny" -> 1)), "alice")
        heavy <- Antifraud.Impl(enabled, List(rules(rule("a", 1))), store).evaluate(ctx)
        forced <- Antifraud.Impl(enabled, List(rules(rule("deny", 0, Some(DecisionAction.Fake)))), store).evaluate(ctx)
      yield assertTrue(heavy.score == 20, heavy.action == DecisionAction.Block, forced.score == 0, forced.action == DecisionAction.Fake)
    },
    test("a shadow rule is listed as such and adds nothing") {
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(blockAt = 5, shadowRules = Set("a", "deny")), "alice")
        decision <- Antifraud.Impl(enabled, List(rules(rule("a", 9), rule("b", 1), rule("deny", 0, Some(DecisionAction.Block)))), store).evaluate(ctx)
      yield assertTrue(decision.score == 1, decision.action == DecisionAction.Allow, decision.firedRules == List("b", "shadow:a", "shadow:deny"))
    },
    test("the limit on codes sent follows the longest prefix a number starts with") {
      val limits = SmsLimits(SmsLimit(0, 0, 0.1), Map("49" -> SmsLimit(2, 0, 0.1), "4915" -> SmsLimit(1, 0, 0.1)))
      val tenant = Some(TenantId("a"))
      for
        (store, _, _) <- storeLayer
        _ <- store.put(base.copy(smsLimits = limits), "alice")
        budget = new SmsBudget(AntifraudTestSettings.smsBudget.copy(prefixLength = 6), () => store.current.smsLimits)
        first = budget.decide(tenant, "491512", at, charge = true)
        second = budget.decide(tenant, "491512", at, charge = true)
        german = (budget.decide(tenant, "491612", at, charge = true), budget.decide(tenant, "491612", at, charge = true), budget.decide(tenant, "491612", at, charge = true))
        elsewhere = (1 to 5).map(_ => budget.decide(tenant, "771012", at, charge = true)).toSet
      yield assertTrue(
        store.current.smsLimits.enabled,
        first == SmsBudget.Decision.Allowed,
        second == SmsBudget.Decision.Refused,
        german == (SmsBudget.Decision.Allowed, SmsBudget.Decision.Allowed, SmsBudget.Decision.Refused),
        elsewhere == Set(SmsBudget.Decision.Allowed),
      )
    },
    test("the velocity rules are built from the limits that are stored") {
      val limit = AntifraudTestSettings.limit.copy(perHour = 2)
      for
        (store, _, _) <- storeLayer
        ruleSet = VelocityRules.ruleSet(AntifraudTestSettings.velocity.copy(enabled = true), store)
        before <- ruleSet.rulesFor(ctx)
        _ <- store.put(base.copy(thresholds = base.thresholds.copy(phone = limit)), "alice")
        after <- ruleSet.rulesFor(ctx)
      yield assertTrue(before.isEmpty, after.map(_.name).contains("velocity_phone_1h"))
    },
  )

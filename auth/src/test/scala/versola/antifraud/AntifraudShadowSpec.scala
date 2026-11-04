package versola.antifraud

import versola.oauth.client.model.ClientId
import versola.util.UnitSpecBase
import versola.util.audit.AuditEvent
import zio.*
import zio.json.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

import java.time.Instant

/** Shadow mode: the engine decides in full and acts on none of it, what is recorded and counted for it, and the tuning that
  * says so (tolerant of a stored row from before the mode existed, and validated across its fields). */
object AntifraudShadowSpec extends UnitSpecBase:

  private val ctx = AntifraudContext(None, ClientId("c"), AntifraudAction.otp_request, None, None, None, Some("1.2.3.4"), None, None, None, Map.empty)

  private def rule(name: String, weight: Int, forces: Option[DecisionAction] = None, hit: Boolean = true) =
    AntifraudRule(name, weight, forces, _ => hit)

  private def set(rs: AntifraudRule*): Antifraud.RuleSet = new Antifraud.RuleSet:
    def rules = ZIO.succeed(rs.toList)

  private val on = AntifraudTestSettings.decision.copy(enabled = true)
  private val enforcing = AntifraudTestSettings.tuning.copy(challengeAt = 20, blockAt = 50)
  private val shadow = enforcing.copy(mode = TuningMode.shadow)

  private def engine(tuning: Tuning, settings: Antifraud.Settings = on)(rs: AntifraudRule*) =
    AntifraudTestSettings.engine(settings, List(set(rs*)), tuning)

  private def count(name: String, labels: (String, String)*): UIO[Double] =
    Metric.counter(name).tagged(labels.map((k, v) => MetricLabel(k, v)).toSet).value.map(_.count)

  private def refused(t: Tuning): Option[String] = AntifraudTuningStore.invalid(t)

  def spec = suite("Shadow mode")(
    suite("the engine")(
      test("decides in full and acts on none of it: score thresholds and forced list actions alike") {
        val rs = List(rule("velocity_ip_1m", 30), rule("velocity_phone_1h", 25))
        for
          challenge <- engine(shadow)(rule("a", 25)).evaluate(ctx)
          block <- engine(shadow)(rs*).evaluate(ctx)
          deny <- engine(shadow)(rule("deny_list:ip", 0, Some(DecisionAction.Block))).evaluate(ctx)
          fake <- engine(shadow)(rule("fake_list:prefix", 0, Some(DecisionAction.Fake))).evaluate(ctx)
          harsher <- engine(shadow)(rule("fake_list:prefix", 0, Some(DecisionAction.Fake)), rule("w", 60)).evaluate(ctx)
          quiet <- engine(shadow)(rule("a", 3)).evaluate(ctx)
        yield assertTrue(
          challenge == Decision(DecisionAction.Allow, 25, List("a"), DecisionAction.Challenge),
          block == Decision(DecisionAction.Allow, 55, List("velocity_ip_1m", "velocity_phone_1h"), DecisionAction.Block),
          deny == Decision(DecisionAction.Allow, 0, List("deny_list:ip"), DecisionAction.Block),
          fake == Decision(DecisionAction.Allow, 0, List("fake_list:prefix"), DecisionAction.Fake),
          harsher.wouldAction == DecisionAction.Block,
          harsher.action == DecisionAction.Allow,
          quiet == Decision(DecisionAction.Allow, 3, List("a"), DecisionAction.Allow),
        )
      },
      test("the same rules in enforce act on what shadow only records") {
        for
          challenge <- engine(enforcing)(rule("a", 25)).evaluate(ctx)
          deny <- engine(enforcing)(rule("deny_list:ip", 0, Some(DecisionAction.Block))).evaluate(ctx)
        yield assertTrue(
          challenge == Decision(DecisionAction.Challenge, 25, List("a"), DecisionAction.Challenge),
          deny.action == DecisionAction.Block,
          deny.wouldAction == DecisionAction.Block,
        )
      },
      test("a decision that is not made in shadow is the plain one: acted on as it says, and would-be the same") {
        val d = Decision(DecisionAction.Block, 9, List("x"))
        assertTrue(
          d.wouldAction == DecisionAction.Block,
          Decision.allow.wouldAction == DecisionAction.Allow,
          !Decision.allow.noteworthy,
          d.noteworthy,
        )
      },
      test("a rule that only records is still listed as shadow:<name> and adds nothing, in either mode") {
        for
          s <- engine(shadow.copy(shadowRules = Set("velocity_ip_1m")))(rule("velocity_ip_1m", 90)).evaluate(ctx)
          e <- engine(enforcing.copy(shadowRules = Set("velocity_ip_1m")))(rule("velocity_ip_1m", 90)).evaluate(ctx)
        yield assertTrue(s == Decision(DecisionAction.Allow, 0, List("shadow:velocity_ip_1m")), e == s)
      },
    ),
    suite("a failed evaluation")(
      test("in shadow is only recorded: failure-action is the would-be action, the request is let through") {
        val boom = rule("boom", 1).copy(test = _ => throw RuntimeException("x"))
        for
          d <- engine(shadow, on.copy(failureAction = DecisionAction.Challenge))(boom).evaluate(ctx)
        yield assertTrue(d == Decision(DecisionAction.Allow, 0, List("antifraud_error"), DecisionAction.Challenge))
      },
      test("in enforce acts as failure-action says") {
        val boom = rule("boom", 1).copy(test = _ => throw RuntimeException("x"))
        for
          d <- engine(enforcing, on.copy(failureAction = DecisionAction.Challenge))(boom).evaluate(ctx)
        yield assertTrue(d == Decision(DecisionAction.Challenge, 0, List("antifraud_error"), DecisionAction.Challenge))
      },
      test("is counted by reason: an error, a timeout, and a tuning that has not been read") {
        val boom = rule("boom", 1).copy(test = _ => throw RuntimeException("x"))
        val slow = new Antifraud.RuleSet { def rules = ZIO.never }
        val unread = new AntifraudTuning:
          def current = Tuning.off
          def available = false
        for
          error0 <- count("antifraud_evaluation_failed_total", "reason" -> "error")
          timeout0 <- count("antifraud_evaluation_failed_total", "reason" -> "timeout")
          unread0 <- count("antifraud_evaluation_failed_total", "reason" -> "tuning_unavailable")
          _ <- engine(shadow)(boom).evaluate(ctx)
          fiber <- Antifraud.Impl(on, List(slow), AntifraudTestSettings.fixed(shadow)).evaluate(ctx).fork
          _ <- TestClock.adjust(1.second)
          _ <- fiber.join
          cold <- Antifraud.Impl(on.copy(failureAction = DecisionAction.Block), List(set(rule("a", 1))), unread).evaluate(ctx)
          error1 <- count("antifraud_evaluation_failed_total", "reason" -> "error")
          timeout1 <- count("antifraud_evaluation_failed_total", "reason" -> "timeout")
          unread1 <- count("antifraud_evaluation_failed_total", "reason" -> "tuning_unavailable")
        yield assertTrue(
          // Counters are the process's, and other specs fail evaluations too: at least these.
          error1 - error0 >= 1.0,
          timeout1 - timeout0 >= 1.0,
          unread1 - unread0 >= 1.0,
          // The mode is not known before the tuning is read, so failure-action applies as it is.
          cold == Decision(DecisionAction.Block, 0, List("antifraud_tuning_unavailable"), DecisionAction.Block),
        )
      },
    ),
    suite("the metrics")(
      test("decisions are counted by the action taken and the one decided; rules by name and whether they only record") {
        val d = Decision(DecisionAction.Allow, 80, List("velocity_ip_1m", "shadow:deny_list:cidr", Antifraud.ErrorRule), DecisionAction.Block)
        for
          decided0 <- count("antifraud_decisions_total", "action" -> "Allow", "would_action" -> "Block")
          real0 <- count("antifraud_rules_fired_total", "rule" -> "velocity_ip_1m", "shadow" -> "false")
          shadowed0 <- count("antifraud_rules_fired_total", "rule" -> "deny_list:cidr", "shadow" -> "true")
          _ <- AntifraudMetrics.decided(d) *> AntifraudMetrics.rulesFired(d.firedRules)
          decided1 <- count("antifraud_decisions_total", "action" -> "Allow", "would_action" -> "Block")
          real1 <- count("antifraud_rules_fired_total", "rule" -> "velocity_ip_1m", "shadow" -> "false")
          shadowed1 <- count("antifraud_rules_fired_total", "rule" -> "deny_list:cidr", "shadow" -> "true")
          errors <- count("antifraud_rules_fired_total", "rule" -> Antifraud.ErrorRule, "shadow" -> "false")
        yield assertTrue(decided1 - decided0 >= 1.0, real1 - real0 >= 1.0, shadowed1 - shadowed0 >= 1.0, errors == 0.0)
      },
      test("the budget's refusals are counted by reason and by mode") {
        for
          cap0 <- count("antifraud_sms_budget_refused_total", "reason" -> "cap", "mode" -> "shadow")
          conversion0 <- count("antifraud_sms_budget_refused_total", "reason" -> "conversion", "mode" -> "enforce")
          _ <- AntifraudMetrics.smsRefused("cap", TuningMode.shadow)
          _ <- AntifraudMetrics.smsRefused("conversion", TuningMode.enforce)
          cap1 <- count("antifraud_sms_budget_refused_total", "reason" -> "cap", "mode" -> "shadow")
          conversion1 <- count("antifraud_sms_budget_refused_total", "reason" -> "conversion", "mode" -> "enforce")
        yield assertTrue(cap1 - cap0 >= 1.0, conversion1 - conversion0 >= 1.0)
      },
      test("what a refresh last read is a time, so that a hang shows as age") {
        val at = Instant.parse("2026-11-02T10:00:00Z")
        // The gauges are the process's: another spec's refresh, on its own clock, may set them in between, so set again.
        def set(name: String, write: UIO[Unit], expected: Double) =
          (write *> Metric.gauge(name).value.map(_.value)).repeatUntil(_ == expected).timeout(5.seconds).map(_.contains(expected))
        for
          tuning <- set("antifraud_tuning_refreshed_timestamp_seconds", AntifraudMetrics.tuningRefreshed(at), at.getEpochSecond.toDouble)
          lists <-
            set("antifraud_lists_refreshed_timestamp_seconds", AntifraudListMetrics.refreshed(at.plusSeconds(5)), at.getEpochSecond.toDouble + 5)
        yield assertTrue(tuning, lists)
      },
    ),
    suite("the tuning")(
      test("is shadow unless it says otherwise, and says enforce as a word") {
        assertTrue(
          Tuning.off.mode == TuningMode.shadow,
          Tuning().mode == TuningMode.shadow,
          enforcing.toJson.contains("\"mode\":\"enforce\""),
          shadow.toJson.contains("\"mode\":\"shadow\""),
          enforcing.toJson.fromJson[Tuning] == Right(enforcing),
        )
      },
      test("reads a stored row that lacks a key, which is what a key added later looks like: the key's default, shadow for the mode") {
        val withoutMode = enforcing.toJson.replace("\"mode\":\"enforce\",", "")
        val sparse = """{"blockAt":80,"pow":{"minDifficulty":10},"smsLimits":{"default":{"sentPerHour":50}}}"""
        val oldRow = shadow.copy(blockAt = 70).toJson.replace("\"mode\":\"shadow\",", "")
        assertTrue(
          !withoutMode.contains("mode"),
          withoutMode.fromJson[Tuning] == Right(enforcing.copy(mode = TuningMode.shadow)),
          "{}".fromJson[Tuning] == Right(Tuning.off),
          sparse.fromJson[Tuning].map(t => (t.mode, t.blockAt, t.pow, t.smsLimits.default.sentPerHour, t.thresholds)) ==
            Right((TuningMode.shadow, 80, PowDifficulty(10, 18, 15), 50, Tuning.off.thresholds)),
          oldRow.fromJson[Tuning] == Right(shadow.copy(blockAt = 70)),
        )
      },
      test("a value that is there but wrong is still an error, not a default") {
        assertTrue("""{"blockAt":"many"}""".fromJson[Tuning].isLeft, """{"mode":"loud"}""".fromJson[Tuning].isLeft)
      },
      test("challengeAt may not be above blockAt when both are set; either alone is fine") {
        assertTrue(
          refused(enforcing.copy(challengeAt = 60, blockAt = 50)).isDefined,
          refused(enforcing.copy(challengeAt = 50, blockAt = 50)).isEmpty,
          refused(enforcing.copy(challengeAt = 60, blockAt = 0)).isEmpty,
          refused(enforcing.copy(challengeAt = 0, blockAt = 60)).isEmpty,
        )
      },
      test("blockAt is at least 10 when set") {
        assertTrue(
          refused(enforcing.copy(challengeAt = 0, blockAt = 9)).isDefined,
          refused(enforcing.copy(challengeAt = 0, blockAt = 1)).isDefined,
          refused(enforcing.copy(challengeAt = 0, blockAt = 10)).isEmpty,
          refused(enforcing.copy(challengeAt = 0, blockAt = 0)).isEmpty,
        )
      },
      test("the proof of work is at most 24 bits") {
        assertTrue(
          refused(enforcing.copy(pow = PowDifficulty(14, 25, 15))).isDefined,
          refused(enforcing.copy(pow = PowDifficulty(14, 24, 15))).isEmpty,
          refused(enforcing.copy(pow = PowDifficulty(32, 32, 15))).isDefined,
        )
      },
      test("the limits on codes are 0 or at least 20, by default and by prefix") {
        def sms(limit: SmsLimit) = enforcing.copy(smsLimits = SmsLimits(limit, Map.empty))
        assertTrue(
          refused(sms(SmsLimit(sentPerHour = 1, minVolume = 0, minConversion = 0.1))).isDefined,
          refused(sms(SmsLimit(sentPerHour = 19, minVolume = 0, minConversion = 0.1))).isDefined,
          refused(sms(SmsLimit(sentPerHour = 0, minVolume = 5, minConversion = 0.1))).isDefined,
          refused(sms(SmsLimit(sentPerHour = 20, minVolume = 20, minConversion = 0.1))).isEmpty,
          refused(sms(SmsLimit(sentPerHour = 0, minVolume = 0, minConversion = 0.1))).isEmpty,
          refused(enforcing.copy(smsLimits =
            SmsLimits(SmsLimit(), Map("49" -> SmsLimit(sentPerHour = 3, minVolume = 0, minConversion = 0.1))),
          )).isDefined,
        )
      },
      test("a rule's weight or shadow flag has to name a rule there is: a typo is refused, a rule of the catalogue is not") {
        assertTrue(
          refused(enforcing.copy(ruleWeights = Map("velocity_phone_1h" -> 10))).isEmpty,
          refused(enforcing.copy(ruleWeights = Map("deny_list:ip" -> 10, "watch_list:email_domain" -> 5))).isEmpty,
          refused(enforcing.copy(shadowRules = Set("velocity_known_user", "shadow_list:ua_hash"))).isEmpty,
          refused(enforcing.copy(ruleWeights = Map("asn_hosting" -> 40), shadowRules = Set("asn_hosting"))).isEmpty,
          refused(enforcing.copy(
            ruleWeights = Map("country_mismatch" -> 40),
            shadowRules = Set("country_mismatch", "country_outside_regions"),
          )).isEmpty,
          refused(enforcing.copy(shadowRules = Set("country_mismatches"))).exists(_.contains("country_mismatches")),
          refused(enforcing.copy(ruleWeights = Map("velocity_phone_2h" -> 10))).exists(_.contains("velocity_phone_2h")),
          refused(enforcing.copy(shadowRules = Set("velocity_fone_1h"))).exists(_.contains("velocity_fone_1h")),
          refused(enforcing.copy(shadowRules = Set(Antifraud.ErrorRule))).isDefined,
        )
      },
      test("the catalogue is the rules the engine's sources contribute: one per key and window, one per kind of list and of value") {
        val names = Antifraud.ruleNames
        assertTrue(
          names.size == 5 * 4 + 1 + 10 * 5 + 1 + 2,
          names.contains("velocity_prefix_24h"),
          names.contains("velocity_known_user"),
          names.contains("fake_list:prefix"),
          names.contains("asn_hosting"),
          names.contains("country_mismatch"),
          names.contains("country_outside_regions"),
          VelocityRules.rules(VelocityRules.Thresholds().copy(
            phone = VelocityRules.Limit(1, 1, 1, 1, 1),
            ip = VelocityRules.Limit(1, 1, 1, 1, 1),
            device = VelocityRules.Limit(1, 1, 1, 1, 1),
            asn = VelocityRules.Limit(1, 1, 1, 1, 1),
            prefix = VelocityRules.Limit(1, 1, 1, 1, 1),
          ))
            .forall(r => names.contains(r.name)),
        )
      },
      test("the store refuses them when put, and stores nothing") {
        val store = AntifraudTuningStore.Impl(new AntifraudSettingsRepository:
          def put(tuning: Tuning, actor: String, at: Instant, expectedVersion: Option[Long], audit: AuditEvent) = ZIO.some(1L)
          def seed(tuning: Tuning, actor: String, at: Instant) = ZIO.succeed(true)
          def find = ZIO.none
          def version = ZIO.succeed(0L))
        for
          typo <- store.put(enforcing.copy(ruleWeights = Map("nope" -> 3)), "alice", None).either
          order <- store.put(enforcing.copy(challengeAt = 90, blockAt = 50), "alice", None).either
        yield assertTrue(typo.left.exists(_.isInstanceOf[InvalidTuning]), order.left.exists(_.isInstanceOf[InvalidTuning]))
      },
    ),
  )

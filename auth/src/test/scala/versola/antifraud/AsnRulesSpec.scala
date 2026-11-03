package versola.antifraud

import versola.oauth.client.model.ClientId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

object AsnRulesSpec extends UnitSpecBase:

  private def ctx(signals: Map[String, String] = Map.empty, asn: Option[Int] = None) = AntifraudContext(
    tenantId = None,
    clientId = ClientId("c"),
    action = AntifraudAction.otp_request,
    subjectHash = Some("h"),
    destPrefix = Some("491512"),
    deviceId = None,
    ip = Some("5.45.64.1"),
    asn = asn,
    userAgent = None,
    ja4 = None,
    signals = signals,
  )

  private val hostingContext = ctx(Map(AsnRules.ClassSignal -> "hosting"), Some(24940))
  private val carrierContext = ctx(Map(AsnRules.ClassSignal -> "other"), Some(64500))

  private val challenging = AntifraudTestSettings.tuning.copy(challengeAt = 20, blockAt = 100)

  private def decide(context: AntifraudContext, tuning: Tuning = challenging, rules: List[Antifraud.RuleSet] = List(AsnRules.ruleSet)) =
    AntifraudTestSettings.engine(AntifraudTestSettings.decision.copy(enabled = true), rules, tuning).evaluate(context)

  def spec = suite("AsnRules")(
    test("asn_hosting is one rule that forces nothing and weighs 20") {
      for rules <- AsnRules.ruleSet.rules
      yield assertTrue(rules.map(_.name) == List("asn_hosting"), rules.map(_.weight) == List(20), rules.flatMap(_.forces).isEmpty)
    },
    test("fires for a hosting network, adding its weight, and for nothing else") {
      for
        hosting <- decide(hostingContext)
        carrier <- decide(carrierContext)
        unknown <- decide(ctx())
      yield assertTrue(
        hosting == Decision(DecisionAction.Challenge, 20, List("asn_hosting")),
        carrier == Decision.allow,
        unknown == Decision.allow,
      )
    },
    test("alone it never refuses: a score of 20 is under any block threshold that is meant to be, and it forces no action") {
      decide(hostingContext, AntifraudTestSettings.tuning.copy(challengeAt = 50, blockAt = 100)).map(d =>
        assertTrue(d == Decision(DecisionAction.Allow, 20, List("asn_hosting"))),
      )
    },
    test("its weight is tunable by name, up and down, and to nothing") {
      for
        heavy <- decide(hostingContext, challenging.copy(blockAt = 60, ruleWeights = Map("asn_hosting" -> 70)))
        light <- decide(hostingContext, challenging.copy(ruleWeights = Map("asn_hosting" -> 5)))
        zero <- decide(hostingContext, challenging.copy(ruleWeights = Map("asn_hosting" -> 0)))
      yield assertTrue(
        heavy == Decision(DecisionAction.Block, 70, List("asn_hosting")),
        light == Decision(DecisionAction.Allow, 5, List("asn_hosting")),
        zero == Decision(DecisionAction.Allow, 0, List("asn_hosting")),
      )
    },
    test("as a shadow rule it is listed and weighs nothing, a dry run before it is made real") {
      decide(hostingContext, challenging.copy(shadowRules = Set("asn_hosting"))).map(d =>
        assertTrue(d == Decision(DecisionAction.Allow, 0, List("shadow:asn_hosting"))),
      )
    },
    test("it adds to what the other rules score, and the class is read from the signal and not from the AS number") {
      val other = new Antifraud.RuleSet:
        def rules = ZIO.succeed(List(AntifraudRule("velocity", 40, None, _ => true)))
      for
        both <- decide(hostingContext, challenging.copy(challengeAt = 50, blockAt = 100), List(other, AsnRules.ruleSet))
        // The AS number alone says nothing: the class decides.
        numberOnly <- decide(ctx(asn = Some(24940)))
      yield assertTrue(
        both == Decision(DecisionAction.Challenge, 60, List("velocity", "asn_hosting")),
        numberOnly == Decision.allow,
      )
    },
    test("every rule of the signal is in the catalogue, so that the tuned settings may weigh it and run it as a shadow rule") {
      for rules <- AsnRules.ruleSet.rules
      yield assertTrue(
        rules.map(_.name).forall(Antifraud.ruleNames.contains),
        AsnRules.ruleNames == rules.map(_.name),
        Antifraud.ruleNames.contains("asn_hosting"),
      )
    },
    test("the signal names are the ones the documentation gives") {
      assertTrue(AsnRules.ClassSignal == "asn_class", AsnRules.HostingRule == "asn_hosting")
    },
  )

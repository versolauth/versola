package versola.antifraud

import versola.oauth.client.model.ClientId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

object GeoRulesSpec extends UnitSpecBase:

  private def ctx(signals: Map[String, String]) = AntifraudContext(
    tenantId = None,
    clientId = ClientId("c"),
    action = AntifraudAction.otp_request,
    subjectHash = Some("h"),
    destPrefix = Some("7701"),
    deviceId = None,
    ip = Some("203.0.113.7"),
    asn = None,
    userAgent = None,
    ja4 = None,
    signals = signals,
  )

  private def geo(
      country: Option[String] = None,
      phone: Option[String] = None,
      regions: Option[String] = None,
      asnClass: Option[String] = None,
  ) = ctx(
    country.map(GeoRules.CountrySignal -> _).toMap ++
      phone.map(GeoRules.PhoneCountrySignal -> _) ++
      regions.map(GeoRules.TenantRegionsSignal -> _) ++
      asnClass.map(AsnRules.ClassSignal -> _),
  )

  private val challenging = AntifraudTestSettings.tuning.copy(challengeAt = 20, blockAt = 100)

  private def decide(context: AntifraudContext, tuning: Tuning = challenging, rules: List[Antifraud.RuleSet] = List(GeoRules.ruleSet)) =
    AntifraudTestSettings.engine(AntifraudTestSettings.decision.copy(enabled = true), rules, tuning).evaluate(context)

  private val mismatch = Decision(DecisionAction.Challenge, 20, List("country_mismatch"))
  private val outside = Decision(DecisionAction.Challenge, 20, List("country_outside_regions"))

  def spec = suite("GeoRules")(
    test("the two rules force nothing and weigh 20 each") {
      for rules <- GeoRules.ruleSet.rules
      yield assertTrue(
        rules.map(_.name) == List("country_mismatch", "country_outside_regions"),
        rules.map(_.weight) == List(20, 20),
        rules.flatMap(_.forces).isEmpty,
      )
    },
    suite("country_mismatch")(
      test("fires when the address country differs from the number's, adding its weight") {
        decide(geo(country = Some("UZ"), phone = Some("KZ"))).map(d => assertTrue(d == mismatch))
      },
      test("does not fire when the two agree") {
        decide(geo(country = Some("KZ"), phone = Some("KZ"))).map(d => assertTrue(d == Decision.allow))
      },
      test("does not fire without an address country, whatever the number's") {
        decide(geo(phone = Some("KZ"))).map(d => assertTrue(d == Decision.allow))
      },
      test("does not fire for a number whose region is not known (or an email address): there is nothing to compare") {
        decide(geo(country = Some("UZ"))).map(d => assertTrue(d == Decision.allow))
      },
      test("does not fire for a hosting network, which says nothing about the person, and does for any other class") {
        for
          hosting <- decide(geo(country = Some("NL"), phone = Some("KZ"), asnClass = Some("hosting")))
          carrier <- decide(geo(country = Some("NL"), phone = Some("KZ"), asnClass = Some("other")))
        yield assertTrue(hosting == Decision.allow, carrier == mismatch)
      },
      test("does not read the tenant's regions, and the other rule does not read the number's country") {
        for
          withRegions <- decide(geo(country = Some("UZ"), phone = Some("UZ"), regions = Some("UZ,KZ")))
          withPhone <- decide(geo(country = Some("UZ"), phone = Some("KZ"), regions = Some("UZ")))
        yield assertTrue(withRegions == Decision.allow, withPhone == mismatch)
      },
    ),
    suite("country_outside_regions")(
      test("fires when the tenant restricts regions and the address country is not among them") {
        decide(geo(country = Some("NL"), regions = Some("KZ,UZ"))).map(d => assertTrue(d == outside))
      },
      test("does not fire when the address country is one of the tenant's regions, first, middle or last") {
        for
          first <- decide(geo(country = Some("KZ"), regions = Some("KZ,UZ,KG")))
          middle <- decide(geo(country = Some("UZ"), regions = Some("KZ,UZ,KG")))
          last <- decide(geo(country = Some("KG"), regions = Some("KZ,UZ,KG")))
          only <- decide(geo(country = Some("KZ"), regions = Some("KZ")))
        yield assertTrue(first == Decision.allow, middle == Decision.allow, last == Decision.allow, only == Decision.allow)
      },
      test("compares whole codes: a country that a region's code merely contains is outside") {
        decide(geo(country = Some("K"), regions = Some("KZ"))).map(d => assertTrue(d == outside))
      },
      test("never fires for a tenant that has not restricted its regions") {
        decide(geo(country = Some("NL"))).map(d => assertTrue(d == Decision.allow))
      },
      test("does not fire without an address country") {
        decide(geo(regions = Some("KZ"))).map(d => assertTrue(d == Decision.allow))
      },
      test("does not fire for a hosting network, and does for any other class") {
        for
          hosting <- decide(geo(country = Some("NL"), regions = Some("KZ"), asnClass = Some("hosting")))
          carrier <- decide(geo(country = Some("NL"), regions = Some("KZ"), asnClass = Some("other")))
        yield assertTrue(hosting == Decision.allow, carrier == outside)
      },
    ),
    test("both fire for one request and their weights add up") {
      decide(geo(country = Some("NL"), phone = Some("KZ"), regions = Some("KZ")), challenging.copy(challengeAt = 40)).map(d =>
        assertTrue(d == Decision(DecisionAction.Challenge, 40, List("country_mismatch", "country_outside_regions"))),
      )
    },
    test("alone, neither refuses: 20 is under any block threshold that is meant to be, and nothing is forced") {
      decide(geo(country = Some("UZ"), phone = Some("KZ")), AntifraudTestSettings.tuning.copy(challengeAt = 50, blockAt = 100)).map(d =>
        assertTrue(d == Decision(DecisionAction.Allow, 20, List("country_mismatch"))),
      )
    },
    test("the weights are tunable by name, up, down and to nothing") {
      val context = geo(country = Some("UZ"), phone = Some("KZ"), regions = Some("KZ"))
      for
        heavy <- decide(context, challenging.copy(blockAt = 60, ruleWeights = Map("country_mismatch" -> 70, "country_outside_regions" -> 0)))
        light <- decide(context, challenging.copy(ruleWeights = Map("country_mismatch" -> 5, "country_outside_regions" -> 5)))
      yield assertTrue(
        heavy == Decision(DecisionAction.Block, 70, List("country_mismatch", "country_outside_regions")),
        light == Decision(DecisionAction.Allow, 10, List("country_mismatch", "country_outside_regions")),
      )
    },
    test("as shadow rules they are listed as `shadow:` and weigh nothing, each on its own") {
      val context = geo(country = Some("UZ"), phone = Some("KZ"), regions = Some("KZ"))
      for
        both <- decide(context, challenging.copy(shadowRules = Set("country_mismatch", "country_outside_regions")))
        one <- decide(context, challenging.copy(shadowRules = Set("country_mismatch")))
      yield assertTrue(
        both == Decision(DecisionAction.Allow, 0, List("shadow:country_mismatch", "shadow:country_outside_regions")),
        one == Decision(DecisionAction.Challenge, 20, List("country_outside_regions", "shadow:country_mismatch")),
      )
    },
    test("they add to what the other rules score") {
      val other = new Antifraud.RuleSet:
        def rules = ZIO.succeed(List(AntifraudRule("velocity", 40, None, _ => true)))
      decide(geo(country = Some("UZ"), phone = Some("KZ")), challenging.copy(challengeAt = 50), List(other, GeoRules.ruleSet)).map(d =>
        assertTrue(d == Decision(DecisionAction.Challenge, 60, List("velocity", "country_mismatch"))),
      )
    },
    test("the signal of the regions is the upper-case, comma-joined, distinct list, and there is none for an unrestricted tenant") {
      assertTrue(
        GeoRules.regionsSignal(List("kz", "UZ", "KZ")).contains("KZ,UZ"),
        GeoRules.regionsSignal(List("KZ")).contains("KZ"),
        GeoRules.regionsSignal(Nil).isEmpty,
        GeoRules.regionsSignal(List("", " ")).isEmpty,
      )
    },
    test("every rule of the signal is in the catalogue, so that the tuned settings may weigh it and run it as a shadow rule") {
      for rules <- GeoRules.ruleSet.rules
      yield assertTrue(
        rules.map(_.name).forall(Antifraud.ruleNames.contains),
        GeoRules.ruleNames == rules.map(_.name),
        Antifraud.ruleNames.contains("country_mismatch"),
        Antifraud.ruleNames.contains("country_outside_regions"),
      )
    },
    test("the signal names are the ones the documentation gives") {
      assertTrue(
        GeoRules.CountrySignal == "country",
        GeoRules.PhoneCountrySignal == "phone_country",
        GeoRules.TenantRegionsSignal == "tenant_regions",
        GeoRules.MismatchRule == "country_mismatch",
        GeoRules.OutsideRegionsRule == "country_outside_regions",
      )
    },
  )

package versola.antifraud

import versola.util.net.AsnClass
import zio.*

/** The country signal: the country an OTP request's address is in, and the two rules on it.
  *
  * [[AntifraudEvents]] puts it into the context's `signals` under [[CountrySignal]] as an ISO 3166-1 alpha-2 code in upper
  * case (`KZ`), from the DB-IP country database (`antifraud.geo.file`) and from nothing else. It is absent without that
  * database, whatever the ASN database knows, and for an address the database has no country for. The country an AS is
  * registered in ([[versola.util.net.AsnInfo.country]]) is deliberately not a fallback: a registry's record says where the
  * holder is, not where its subscribers are (a satellite ISP registered in one country serves the world), and under one name
  * an operator could not tell a geolocated address from a registration, nor know what a `country` deny acts on. The other
  * reader of the signal is the `country` list entry ([[ListSnapshot]]), which an operator uses to deny, fake or watch a
  * country.
  *
  * Two rules read it, `country_mismatch` and `country_outside_regions`. Both add their weight to the score and force
  * nothing, both weigh 20 (one rule of the kind never decides alone), and both are meant to start as shadow rules
  * (`shadowRules`): an honest population triggers them. Someone roaming abroad asks for a code for their own number from
  * a foreign network, and a traveller, a student or an expatriate signs in to a tenant of their home country from where
  * they are; only the fired rules in `antifraud.rules` of the `receive-http` line and `af_event.rules` show how large that
  * population is for a tenant, and the operator weighs the rule against it before it counts.
  *
  * Neither rule fires for a request from a hosting network ([[AsnClass.hosting]]): the address of a data centre, a cloud or a
  * proxy is where a VPN exit is, which says nothing about where the person is, so the address's country is no evidence of
  * anything in that case, and `asn_hosting` ([[AsnRules]]) is the rule that speaks of such a network. The rules count what
  * a person's own carrier or ISP says, and that is the class `other` and the class unknown.
  *
  * A rule's `test` is pure, so what they read is in the context's `signals`, put there by [[AntifraudEvents]]: the country
  * of the address ([[CountrySignal]]), the country of the number ([[PhoneCountrySignal]], from its E.164 country calling
  * code, absent for an email address and for a number whose region cannot be told) and the countries the tenant takes
  * ([[TenantRegionsSignal]], absent for a tenant that has not restricted `phoneRegions`). A signal that is not there makes the
  * rule that needs it not fire.
  */
object GeoRules:

  /** The [[AntifraudContext.signals]] key of the country: an upper-case ISO alpha-2 code. */
  val CountrySignal = "country"

  /** The key of the country of the destination number: an upper-case ISO alpha-2 code, only when it is known. */
  val PhoneCountrySignal = "phone_country"

  /** The key of the countries the tenant allows (`phoneRegions`): upper-case ISO alpha-2 codes joined with a comma, only when
    * the tenant restricts them. */
  val TenantRegionsSignal = "tenant_regions"

  val MismatchRule = "country_mismatch"

  val OutsideRegionsRule = "country_outside_regions"

  /** What each rule adds to the score unless the tuned `ruleWeights` says otherwise. */
  val MismatchWeight = 20

  val OutsideRegionsWeight = 20

  /** The separator of [[TenantRegionsSignal]]. */
  private val RegionSeparator = ","

  /** The value of [[TenantRegionsSignal]] for these regions, `None` when there is nothing to restrict to. */
  def regionsSignal(regions: Seq[String]): Option[String] =
    Some(regions.map(_.trim.toUpperCase).filter(_.nonEmpty).distinct).filter(_.nonEmpty).map(_.mkString(RegionSeparator))

  /** The address is in a data centre, a cloud or a hosting provider, whose country says nothing about the person. */
  private def hosted(ctx: AntifraudContext): Boolean = ctx.signals.get(AsnRules.ClassSignal).contains(AsnClass.hosting.toString)

  val mismatch: AntifraudRule = AntifraudRule(
    name = MismatchRule,
    weight = MismatchWeight,
    forces = None,
    test = ctx =>
      !hosted(ctx) && (for
        country <- ctx.signals.get(CountrySignal)
        phone <- ctx.signals.get(PhoneCountrySignal)
      yield country != phone).contains(true),
  )

  val outsideRegions: AntifraudRule = AntifraudRule(
    name = OutsideRegionsRule,
    weight = OutsideRegionsWeight,
    forces = None,
    test = ctx =>
      !hosted(ctx) && (for
        country <- ctx.signals.get(CountrySignal)
        regions <- ctx.signals.get(TenantRegionsSignal)
      yield !regions.split(RegionSeparator).contains(country)).contains(true),
  )

  /** The names of the rules of the signal, for [[Antifraud.ruleNames]]. */
  val ruleNames: List[String] = List(MismatchRule, OutsideRegionsRule)

  /** The rules of the signal, for [[Antifraud.live]]. */
  val ruleSet: Antifraud.RuleSet = new Antifraud.RuleSet:
    override def rules: UIO[List[AntifraudRule]] = ZIO.succeed(List(mismatch, outsideRegions))

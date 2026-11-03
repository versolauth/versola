package versola.antifraud

import versola.util.net.AsnClass
import zio.*

/** The rule on the kind of network a request comes from (P1-3, the ASN signal).
  *
  * `asn_hosting` fires when the address belongs to an autonomous system whose name says it is a data centre, a cloud or a
  * hosting provider (see [[versola.util.net.AsnClassifier]]): addresses of that kind belong to servers, proxies and VPN exits
  * far more often than to people. It adds its weight to the score and forces nothing, and it is weighed low (20: one rule
  * of the kind never decides alone, and some honest users are behind a VPN), so that an operator who sees it in the fired
  * rules (`antifraud.rules` of the `receive-http` line, `af_event.rules`) can run it as a shadow rule first, tune its weight
  * (`ruleWeights`), or correct a network by hand with an `asn` list entry, which is exact where the name is a guess.
  *
  * A rule's `test` is pure, so what it reads is in the context's `signals`, put there by [[AntifraudEvents]] from the
  * address: [[ClassSignal]] (`hosting` or `other`). It is not there without a database (the feature is off, or the address
  * is in no AS), and the rule does not fire. The country of the AS is no signal: the country signal ([[GeoRules]]) comes
  * from the country database alone.
  *
  * Counting requests per AS needs no rule here: `asn` is a key of [[VelocityRules]], filled from the same lookup, with a
  * limit of its own (`thresholds.asn`, 0 for none by default). An AS is a whole network, so a limit on one is a limit on
  * everyone in it: a mobile carrier's AS is millions of subscribers, and a limit set low enough for a hosting provider's
  * would refuse a town. That is why its weight stays 20 and its limits 0 until an operator sets one for an AS that the
  * traffic shows is abused. (An `asn` entry cannot be an `allow`, for the same reason: it would exempt a whole network.)
  */
object AsnRules:

  /** The [[AntifraudContext.signals]] key of the class of the network: `hosting` or `other`. */
  val ClassSignal = "asn_class"

  val HostingRule = "asn_hosting"

  /** What the rule adds to the score unless the tuned `ruleWeights` says otherwise. */
  val HostingWeight = 20

  val hosting: AntifraudRule = AntifraudRule(
    name = HostingRule,
    weight = HostingWeight,
    forces = None,
    test = _.signals.get(ClassSignal).contains(AsnClass.hosting.toString),
  )

  /** The names of the rules of the signal, for [[Antifraud.ruleNames]]. */
  val ruleNames: List[String] = List(HostingRule)

  /** The rules of the signal, for [[Antifraud.live]]. */
  val ruleSet: Antifraud.RuleSet = new Antifraud.RuleSet:
    override def rules: UIO[List[AntifraudRule]] = ZIO.succeed(List(hosting))

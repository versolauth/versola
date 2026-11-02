package versola.util.net

import java.util.Locale

/** What kind of network an autonomous system is, as far as its name says. */
enum AsnClass:
  /** A data centre, a cloud or a hosting provider: addresses that servers have and people do not, so a sign-in from one is
    * a script, a proxy or a VPN far more often than a person. */
  case hosting

  /** Everything else: a carrier, a broadband provider, a company, or a network the name does not give away. */
  case other

/** Tells a hosting network from the description of its AS (the registry's name of the holder): `hosting` when the name
  * contains one of the keywords, in any case, else `other`.
  *
  * A name is all there is to go by, so this is a heuristic and errs both ways: a keyword also matches inside other words
  * (`colo` is in `Colombia`), and a provider with an unremarkable name is `other`. The rule that uses it is therefore
  * weighed low, and an operator corrects the odd network with an `asn` list entry, which is exact.
  */
final class AsnClassifier(keywords: List[String]):
  private val needles: Array[String] = keywords.map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).distinct.toArray

  def classify(info: AsnInfo): AsnClass = classify(info.name)

  def classify(name: String): AsnClass =
    val lower = name.toLowerCase(Locale.ROOT)
    if needles.exists(lower.contains) then AsnClass.hosting else AsnClass.other

object AsnClassifier:

  /** The keywords an `antifraud.asn` block without `hosting-keywords` has. */
  val defaultKeywords: List[String] = List(
    "hosting",
    "host",
    "cloud",
    "datacenter",
    "data center",
    "data-center",
    "server",
    "servers",
    "vps",
    "dedicated",
    "colocation",
    "colo",
    "amazon",
    "aws",
    "google",
    "microsoft",
    "azure",
    "digitalocean",
    "ovh",
    "hetzner",
    "linode",
    "akamai",
    "vultr",
    "choopa",
    "alibaba",
    "oracle",
    "contabo",
    "leaseweb",
    "m247",
    "scaleway",
    "online s.a.s",
    "ionos",
    "godaddy",
    "rackspace",
    "tencent",
    "huawei cloud",
  )

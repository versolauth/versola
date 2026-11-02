package versola.util.net

import java.util.Locale
import java.util.regex.Pattern

/** What kind of network an autonomous system is, as far as its name says. */
enum AsnClass:
  /** A data centre, a cloud or a hosting provider: addresses that servers have and people do not, so a sign-in from one is
    * a script, a proxy or a VPN far more often than a person. */
  case hosting

  /** Everything else: a carrier, a broadband provider, a company, or a network the name does not give away. */
  case other

/** Tells a hosting network from the description of its AS (the registry's name of the holder): `hosting` when the name
  * contains one of the keywords as a whole word or phrase, in any case, else `other`.
  *
  * A keyword matches only between characters that are not letters or digits (or at an end of the name), so `cloud` is in
  * `Acme Cloud Ltd` and in `ACME-CLOUD`, not in `Cloudy Networks`, and `data center` is found in `Data  Center` (runs of
  * white space count as one). That keeps a short word from hitting inside the name of a carrier, but a name is still all
  * there is to go by, so this is a heuristic: a provider with an unremarkable name is `other`, and a carrier that has the
  * word in its name (`Cloud` in a mobile operator's brand) is `hosting`. The rule that uses it is therefore weighed low,
  * and an operator corrects the odd network with an `asn` list entry, which is exact.
  */
final class AsnClassifier(keywords: List[String]):
  private val patterns: Array[Pattern] =
    keywords
      .map(k => normalize(k))
      .filter(_.nonEmpty)
      .distinct
      .map(k => Pattern.compile(s"(?<![\\p{L}\\p{N}])${Pattern.quote(k)}(?![\\p{L}\\p{N}])"))
      .toArray

  def classify(info: AsnInfo): AsnClass = classify(info.name)

  def classify(name: String): AsnClass =
    val normalized = normalize(name)
    if patterns.exists(_.matcher(normalized).find()) then AsnClass.hosting else AsnClass.other

  private def normalize(text: String): String = text.trim.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")

object AsnClassifier:

  /** The keywords an `antifraud.asn` block without `hosting-keywords` has. */
  val defaultKeywords: List[String] = List(
    "hosting",
    "cloud",
    "datacenter",
    "data center",
    "data-center",
    "vps",
    "dedicated",
    "colocation",
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

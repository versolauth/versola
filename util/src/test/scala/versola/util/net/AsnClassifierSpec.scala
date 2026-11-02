package versola.util.net

import zio.test.*

object AsnClassifierSpec extends ZIOSpecDefault:

  private val classifier = AsnClassifier(AsnClassifier.defaultKeywords)

  private def classify(name: String): AsnClass = classifier.classify(AsnInfo(asn = 1, country = "ZZ", name = name))

  def spec = suite("AsnClassifier")(
    test("an AS whose name has a hosting keyword is hosting, in any case") {
      assertTrue(
        classify("AMAZON-02 - Amazon.com, Inc.") == AsnClass.hosting,
        classify("HETZNER-AS Hetzner Online GmbH") == AsnClass.hosting,
        classify("DIGITALOCEAN-ASN") == AsnClass.hosting,
        classify("OVH SAS") == AsnClass.hosting,
        classify("Contabo GmbH") == AsnClass.hosting,
        classify("M247 Europe SRL") == AsnClass.hosting,
        classify("Some Data Center Ltd") == AsnClass.hosting,
        classify("acme data-center") == AsnClass.hosting,
        classify("Online S.A.S.") == AsnClass.hosting,
        classify("Huawei Cloud Service") == AsnClass.hosting,
        classify("cheap VPS provider") == AsnClass.hosting,
      )
    },
    test("an AS of a carrier or a broadband provider is other") {
      assertTrue(
        classify("KAZTELECOM-AS JSC Kazakhtelecom") == AsnClass.other,
        classify("Deutsche Telekom AG") == AsnClass.other,
        classify("Beeline Kazakhstan") == AsnClass.other,
        classify("") == AsnClass.other,
      )
    },
    test("the providers' own names are hosting") {
      assertTrue(
        classify("Hetzner Online GmbH") == AsnClass.hosting,
        classify("DigitalOcean, LLC") == AsnClass.hosting,
        classify("OVH SAS") == AsnClass.hosting,
        classify("Contabo GmbH") == AsnClass.hosting,
        classify("Amazon.com, Inc.") == AsnClass.hosting,
        classify("Choopa, LLC") == AsnClass.hosting,
      )
    },
    test("a keyword is a whole word: it is not found inside a longer one, so a carrier is not taken for a hosting provider") {
      assertTrue(
        classify("Colombia Movil") == AsnClass.other,
        classify("Ghostnet") == AsnClass.other,
        classify("Observer Networks") == AsnClass.other,
        classify("Colonial Broadband") == AsnClass.other,
        classify("Cloudy Networks") == AsnClass.other,
        classify("Undedicated Telecom") == AsnClass.other,
        classify("Hostelworld Wifi") == AsnClass.other,
        classify("Amazonas Telecom") == AsnClass.other,
      )
    },
    test("a keyword is found between any characters that are not letters or digits, and a phrase over any white space") {
      assertTrue(
        classify("ACME-CLOUD") == AsnClass.hosting,
        classify("(cloud)") == AsnClass.hosting,
        classify("Acme Cloud, Ltd.") == AsnClass.hosting,
        classify("Some  Data \t Center Ltd") == AsnClass.hosting,
        classify("cloud") == AsnClass.hosting,
        classify("Cloud9 Networks") == AsnClass.other,
        classify("Hosting") == AsnClass.hosting,
      )
    },
    test("the generic words of the old list are not keywords: host, server, servers and colo hit carriers") {
      assertTrue(
        !AsnClassifier.defaultKeywords.exists(Set("host", "server", "servers", "colo").contains),
        classify("Server Farm Telecom") == AsnClass.other,
        classify("Colo Wireless") == AsnClass.other,
      )
    },
    test("the keywords are the operator's: only they decide, in any case, and a blank one matches nothing") {
      val own = AsnClassifier(List("  Telekom ", "", "   "))
      assertTrue(
        own.classify("Deutsche TELEKOM AG") == AsnClass.hosting,
        own.classify("Hetzner Online GmbH") == AsnClass.other,
        own.classify("anything at all") == AsnClass.other,
      )
    },
    test("an empty list of keywords classifies nothing as hosting") {
      assertTrue(AsnClassifier(Nil).classify("Hetzner Online GmbH") == AsnClass.other)
    },
    test("the default keywords are the ones the documentation lists") {
      assertTrue(
        AsnClassifier.defaultKeywords.size == 32,
        AsnClassifier.defaultKeywords.head == "hosting",
        AsnClassifier.defaultKeywords.contains("huawei cloud"),
        AsnClassifier.defaultKeywords == AsnClassifier.defaultKeywords.distinct,
        AsnClassifier.defaultKeywords.forall(k => k == k.toLowerCase),
      )
    },
  )

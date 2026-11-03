package versola.util.net

import com.typesafe.config.ConfigFactory
import zio.*
import zio.config.typesafe.*
import zio.test.*

object AsnSettingsSpec extends ZIOSpecDefault:

  /** As the services read it: the block under `antifraud.asn`, from a kebab-case config. */
  private def load(config: String): Task[AsnSettings] =
    ZIO
      .attempt(ConfigFactory.parseString(config).resolve())
      .flatMap(ConfigProvider.fromTypesafeConfigZIO(_))
      .flatMap(_.kebabCase.load(Config.Nested("antifraud", Config.Nested("asn", AsnSettings.config))))

  def spec = suite("AsnSettings")(
    test("a config with no antifraud block at all, or no asn block, means the feature is off, so an existing config keeps loading") {
      for
        none <- load("env = \"test\"")
        withoutAsn <- load("antifraud { retention = \"14 days\" }")
      yield assertTrue(none == AsnSettings.off, withoutAsn == AsnSettings.off, !none.enabled)
    },
    test("reads the keys of the block, and the keywords are the default ones until the block names its own") {
      for
        full <- load(
          """antifraud.asn { file = "/var/lib/versola/asn.tsv.gz", url = "https://iptoasn.com/data/ip2asn-combined.tsv.gz", refresh-interval = "6 hours", hosting-keywords = ["hosting", "Cloud"] }""",
        )
        fileOnly <- load("""antifraud.asn { file = "/var/lib/versola/asn.tsv" }""")
      yield assertTrue(
        full == AsnSettings(
          file = "/var/lib/versola/asn.tsv.gz",
          url = "https://iptoasn.com/data/ip2asn-combined.tsv.gz",
          refreshInterval = 6.hours,
          hostingKeywords = List("hosting", "Cloud"),
        ),
        full.enabled,
        fileOnly == AsnSettings.off.copy(file = "/var/lib/versola/asn.tsv"),
        fileOnly.enabled,
        fileOnly.refreshInterval == 24.hours,
        fileOnly.hostingKeywords == AsnClassifier.defaultKeywords,
      )
    },
    test("an empty list of keywords is the operator's choice and is kept: nothing is hosting by name") {
      load("""antifraud.asn { file = "/x", hosting-keywords = [] }""").map(settings => assertTrue(settings.hostingKeywords.isEmpty))
    },
    test("an empty file with a url is off: the file is where the database is kept") {
      load("""antifraud.asn { file = "", url = "https://iptoasn.com/data/ip2asn-combined.tsv.gz" }""").map(settings => assertTrue(!settings.enabled))
    },
    test("a url that is not http or https, and an interval of zero or less, fail the start rather than read as some default") {
      for
        ftp <- load("""antifraud.asn { file = "/x", url = "ftp://example.com/asn" }""").either
        garbage <- load("""antifraud.asn { file = "/x", url = "not a url" }""").either
        zero <- load("""antifraud.asn { file = "/x", refresh-interval = "0 seconds" }""").either
        http <- load("""antifraud.asn { file = "/x", url = "http://mirror.internal/asn.tsv.gz" }""").either
      yield assertTrue(ftp.isLeft, garbage.isLeft, zero.isLeft, http.isRight)
    },
  )

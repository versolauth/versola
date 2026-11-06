package versola.util.net

import com.typesafe.config.ConfigFactory
import zio.*
import zio.config.typesafe.*
import zio.test.*

import java.nio.charset.StandardCharsets

object GeoSettingsSpec extends ZIOSpecDefault:

  /** As the services read it: the block under `antifraud.geo`, from a kebab-case config. */
  private def load(config: String): Task[GeoSettings] =
    ZIO
      .attempt(ConfigFactory.parseString(config).resolve())
      .flatMap(ConfigProvider.fromTypesafeConfigZIO(_))
      .flatMap(_.kebabCase.load(Config.Nested("antifraud", Config.Nested("geo", GeoSettings.config))))

  private def urlOf(settings: GeoSettings): String = String(settings.url, StandardCharsets.UTF_8)

  def spec = suite("GeoSettings")(
    test("a config with no antifraud block at all, or no geo block, means the feature is off, so an existing config keeps loading") {
      for
        none <- load("env = \"test\"")
        withoutGeo <- load("antifraud { retention = \"14 days\" }")
      yield assertTrue(
        none.file.isEmpty,
        urlOf(none).isEmpty,
        none.refreshInterval == 7.days,
        withoutGeo.file.isEmpty,
        !none.enabled,
        !withoutGeo.enabled,
        GeoSettings.off.refreshInterval == 7.days,
      )
    },
    test("reads the keys of the block, the interval being seven days until it is set") {
      for
        full <- load(
          """antifraud.geo { file = "/var/lib/versola/geo.csv.gz", url = "https://download.db-ip.com/free/dbip-country-lite-{yyyy}-{mm}.csv.gz", refresh-interval = "3 days" }""",
        )
        fileOnly <- load("""antifraud.geo { file = "/var/lib/versola/geo.csv" }""")
      yield assertTrue(
        full.file == "/var/lib/versola/geo.csv.gz",
        urlOf(full) == "https://download.db-ip.com/free/dbip-country-lite-{yyyy}-{mm}.csv.gz",
        full.refreshInterval == 3.days,
        full.enabled,
        fileOnly.enabled,
        urlOf(fileOnly).isEmpty,
        fileOnly.refreshInterval == 7.days,
      )
    },
    test("an empty file with a url is off: the file is where the database is kept") {
      load("""antifraud.geo { file = "", url = "https://download.db-ip.com/free/x.csv.gz" }""").map(settings => assertTrue(!settings.enabled))
    },
    test("a url that is not http or https, and an interval of zero or less, fail the start rather than read as some default") {
      for
        ftp <- load("""antifraud.geo { file = "/x", url = "ftp://example.com/geo" }""").either
        garbage <- load("""antifraud.geo { file = "/x", url = "not a url" }""").either
        zero <- load("""antifraud.geo { file = "/x", refresh-interval = "0 seconds" }""").either
        http <- load("""antifraud.geo { file = "/x", url = "http://mirror.internal/geo-{yyyy}-{mm}.csv.gz" }""").either
      yield assertTrue(ftp.isLeft, garbage.isLeft, zero.isLeft, http.isRight)
    },
    test("a failed start does not say the url, which may carry a key") {
      load("""antifraud.geo { file = "/x", url = "ftp://example.com/geo?key=s3cr3t-value" }""").either.map: result =>
        assertTrue(result.isLeft, !result.left.toOption.exists(_.toString.contains("s3cr3t-value")))
    },
  )

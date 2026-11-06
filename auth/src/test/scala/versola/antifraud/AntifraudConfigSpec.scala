package versola.antifraud

import com.typesafe.config.ConfigFactory
import versola.util.UnitSpecBase
import versola.util.net.{AsnSettings, GeoSettings}
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** The `antifraud` block a deployment's env.conf has: every key is required, and the block `scripts/gen-env.scala` writes
  * parses. What is tuned during an attack, and the sizes of the components, are not keys. */
object AntifraudConfigSpec extends UnitSpecBase:

  /** The block as gen-env writes it, from its source. */
  val generated: String =
    val file = "scripts/gen-env.scala"
    val path = List(file, s"../../$file", s"../$file", s"../../../$file").map(Paths.get(_)).find(Files.exists(_)).get
    val lines = Files.readAllLines(path).toArray.toList.map(_.toString)
    val from = lines.indexWhere(_.trim.startsWith("|antifraud {"))
    val to = lines.indexWhere(_.trim.startsWith("|$authMutualTlsBlock"), from)
    lines.slice(from, to).map(_.trim.stripPrefix("|")).mkString("\n")

  private def load[A](envConfig: String, config: Config[A]): Task[A] =
    ZIO.attempt(
      ConfigFactory.parseString(envConfig).resolve(),
    ).flatMap(cfg => ConfigProvider.fromTypesafeConfigZIO(cfg)).flatMap(_.kebabCase.load(config))

  private def events(envConfig: String) = load(envConfig, Config.Nested("antifraud", deriveConfig[AntifraudEvents.Settings]))

  def spec = suite("Antifraud config")(
    test("the block gen-env writes parses into every settings class, and everything is off") {
      for
        all <- events(generated)
        lists <- load(generated, Config.Nested("antifraud", Config.Nested("lists", deriveConfig[AntifraudLists.Settings])))
        detectors <- load(generated, Config.Nested("antifraud", Config.Nested("detectors", deriveConfig[AntifraudDetectors.Settings])))
        challenge <- load(generated, Config.Nested("antifraud", Config.Nested("challenge", deriveConfig[PowChallengeVerifier.Settings])))
        tuning <- load(generated, Config.Nested("antifraud", Config.Nested("tuning", deriveConfig[AntifraudTuningStore.Settings])))
        asn <- load(generated, Config.Nested("antifraud", Config.Nested("asn", AsnSettings.config)))
        geo <- load(generated, Config.Nested("antifraud", Config.Nested("geo", GeoSettings.config)))
      yield assertTrue(
        all == AntifraudTestSettings.events,
        lists == AntifraudTestSettings.lists,
        detectors == AntifraudTestSettings.detectors,
        challenge == AntifraudTestSettings.challenge,
        tuning == AntifraudTuningStore.Settings(refreshInterval = 5.seconds),
        asn.copy(url = AntifraudTestSettings.asn.url) == AntifraudTestSettings.asn,
        asn.url.length == 0,
        !asn.enabled,
        geo.copy(url = AntifraudTestSettings.geo.url) == AntifraudTestSettings.geo,
        geo.url.length == 0,
        geo.refreshInterval == 7.days,
        !geo.enabled,
      )
    },
    test("the asn block is the one block a config may leave out: without it the feature is off") {
      val lines = generated.linesIterator.toList
      val from = lines.indexWhere(_.trim.startsWith("# The ASN of a client address"))
      val to = lines.indexWhere(_.trim.startsWith("}"), lines.indexWhere(_.trim.startsWith("asn {")))
      val without = (lines.take(from) ++ lines.drop(to + 1)).mkString("\n")
      for
        loaded <- events(without)
        asn <- load(without, Config.Nested("antifraud", Config.Nested("asn", AsnSettings.config)))
      yield assertTrue(
        from > 0,
        to > from,
        !without.contains("asn {"),
        loaded == AntifraudTestSettings.events,
        asn.copy(url = AsnSettings.off.url) == AsnSettings.off,
      )
    },
    test("the geo block is the other one a config may leave out: without it the feature is off") {
      val lines = generated.linesIterator.toList
      val from = lines.indexWhere(_.trim.startsWith("# The country of a client address"))
      val to = lines.indexWhere(_.trim.startsWith("}"), lines.indexWhere(_.trim.startsWith("geo {")))
      val without = (lines.take(from) ++ lines.drop(to + 1)).mkString("\n")
      for
        loaded <- events(without)
        geo <- load(without, Config.Nested("antifraud", Config.Nested("geo", GeoSettings.config)))
      yield assertTrue(
        from > 0,
        to > from,
        !without.contains("geo {"),
        loaded == AntifraudTestSettings.events,
        geo.copy(url = GeoSettings.off.url) == GeoSettings.off,
        !geo.enabled,
      )
    },
    test("a geo block with a file and a url that has the month in it is read as written") {
      val written = generated.replace(
        "file             = \"\"          # path of dbip-country",
        "file             = \"/var/lib/versola/geo.csv.gz\"          # path of dbip-country",
      )
        .replace(
          "url              = \"\"          # where to download a fresher one to `file`, `",
          "url              = \"https://download.db-ip.com/free/dbip-country-lite-{yyyy}-{mm}.csv.gz\"          # where to download a fresher one to `file`, `",
        )
      load(written, Config.Nested("antifraud", Config.Nested("geo", GeoSettings.config))).map: geo =>
        assertTrue(
          geo.file == "/var/lib/versola/geo.csv.gz",
          geo.enabled,
          String(geo.url, StandardCharsets.UTF_8) == "https://download.db-ip.com/free/dbip-country-lite-{yyyy}-{mm}.csv.gz",
        )
    },
    test("an asn block with a file and keywords is read as written") {
      val written = generated
        .replace("file             = \"\"", "file             = \"/var/lib/versola/asn.tsv.gz\"")
        .replace("# hosting-keywords = [\"hosting\", \"cloud\", \"datacenter\"]", "hosting-keywords = [\"hosting\", \"cloud\"]")
      load(written, Config.Nested("antifraud", Config.Nested("asn", AsnSettings.config))).map: asn =>
        assertTrue(asn.file == "/var/lib/versola/asn.tsv.gz", asn.enabled, asn.hostingKeywords == List("hosting", "cloud"))
    },
    test("a key a deployment leaves out is an error, not a default") {
      val without = generated.replace("retention          = \"14 days\"", "")
      events(without).either.map(result => assertTrue(result.isLeft))
    },
    test("failure-action accepts allow, challenge, fake and block, and neither delay nor review") {
      def action(name: String) =
        events(generated.replace("failure-action = \"allow\"", s"failure-action = \"$name\"")).either.map(_.map(_.decision.failureAction))
      for
        challenge <- action("challenge")
        fake <- action("FAKE")
        delay <- action("delay")
        review <- action("review")
      yield assertTrue(challenge == Right(DecisionAction.Challenge), fake == Right(DecisionAction.Fake), delay.isLeft, review.isLeft)
    },
    test("a config written before max-broad-ttl existed keeps working with 30 days, and the key sets it") {
      val lists = Config.Nested("antifraud", Config.Nested("lists", deriveConfig[AntifraudLists.Settings]))
      val without = generated.replace("max-broad-ttl    = \"30 days\"", "")
      for
        old <- load(without, lists)
        set <- load(generated.replace("max-broad-ttl    = \"30 days\"", "max-broad-ttl = \"7 days\""), lists)
      yield assertTrue(!without.contains("max-broad-ttl"), old.maxBroadTtl == 30.days, set.maxBroadTtl == 7.days, generated.contains("max-broad-ttl"))
    },
    test("a stats-interval of a fraction of a minute is refused, and a whole number of minutes is not") {
      def interval(text: String) = events(generated.replace("stats-interval     = \"1 minute\"", s"stats-interval = \"$text\"")).map(_.statsInterval)
      for
        five <- interval("5 minutes")
        seconds <- interval("90 seconds")
      yield assertTrue(
        five == 5.minutes,
        // The configuration loads it; the application refuses it when it builds its settings (AntifraudEvents.statsIntervalError).
        AntifraudEvents.statsIntervalError(five).isEmpty,
        AntifraudEvents.statsIntervalError(seconds).isDefined,
      )
    },
    test("what is tuned during an attack, and the sizes of the components, are not keys of the block") {
      val keys = ConfigFactory.parseString(generated).getConfig("antifraud")
      val absent = List(
        "decision.block-at",
        "decision.challenge-at",
        "velocity.thresholds",
        "velocity.max-keys",
        "velocity.stripes",
        "sms-budget.sent-per-hour",
        "sms-budget.max-ranges",
        "challenge.min-difficulty",
        "lists.watch-weight",
        "detectors.statement-timeout",
        "queue-capacity",
        "batch-size",
      )
      assertTrue(absent.forall(!keys.hasPath(_)))
    },
  )

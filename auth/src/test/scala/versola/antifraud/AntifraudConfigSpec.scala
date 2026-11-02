package versola.antifraud

import com.typesafe.config.ConfigFactory
import versola.util.UnitSpecBase
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.*
import zio.test.*

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
      yield assertTrue(
        all == AntifraudTestSettings.events,
        lists == AntifraudTestSettings.lists,
        detectors == AntifraudTestSettings.detectors,
        challenge == AntifraudTestSettings.challenge,
        tuning == AntifraudTuningStore.Settings(refreshInterval = 5.seconds),
      )
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

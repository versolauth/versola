package versola.antifraud

import versola.util.UnitSpecBase
import versola.util.http.VersolaApp
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.*
import zio.test.*

import java.nio.file.Files

/** The keys of the `antifraud` block parse from `versola-defaults.conf`, with an env.conf and the overlay file the
  * chart renders laid over it. */
object AntifraudConfigSpec extends UnitSpecBase:

  /** Read the way `auth` reads: the env.conf (and overlay) over the shipped defaults, kebab-case keys. */
  def load[A](envConfig: String, overlay: Option[java.io.File], config: Config[A]): Task[A] =
    ConfigProvider.fromTypesafeConfigZIO(VersolaApp.layered(envConfig, overlay)).flatMap(_.kebabCase.load(config))

  private def events(envConfig: String, overlay: Option[java.io.File] = None) =
    load(envConfig, overlay, Config.Nested("antifraud", deriveConfig[AntifraudEvents.Settings]))

  private val deployment: String =
    """antifraud {
      |  sms-budget { prefix-length = 6, max-ranges = 500 }
      |  decision { enabled = true, timeout = "50 milliseconds", failure-action = "allow" }
      |  velocity { enabled = true, max-keys = 1000, stripes = 4, flush-interval = "5 seconds" }
      |  tuning { refresh-interval = "2 seconds" }
      |  lists { enabled = true, max-traffic-share = 0.1 }
      |  detectors { enabled = true, interval = "30 seconds", statement-timeout = "3 seconds", fake-verify { min-fake-verifies = 3 } }
      |}""".stripMargin

  def spec = suite("Antifraud config")(
    test("every key is there without any config, and nothing is on") {
      val all = AntifraudTestSettings.events
      assertTrue(
        all.queueCapacity == 20000,
        all.retention == 14.days,
        all.statsPrefixLength == 4,
        all.smsBudget == SmsBudget.Settings(prefixLength = 4, maxRanges = 10000),
        !all.decision.enabled,
        all.decision.failureAction == DecisionAction.Challenge,
        all.decision.timeout == 20.millis,
        !all.velocity.enabled,
        all.velocity.maxKeys == 20000,
        !AntifraudTestSettings.lists.enabled,
        AntifraudTestSettings.lists.refreshInterval == 5.seconds,
        !AntifraudTestSettings.detectors.enabled,
        AntifraudTestSettings.detectors.statementTimeout == 10.seconds,
        AntifraudTestSettings.detectors.prefixAnomaly.spikeFactor == 5.0,
        AntifraudTestSettings.detectors.fakeVerify.minFakeVerifies == 5,
      )
    },
    test("what is tuned during an attack is not in the configuration") {
      val keys = VersolaApp.layered("", None).getConfig("antifraud")
      val tunables = List(
        "decision.delay-at",
        "decision.block-at",
        "decision.delay",
        "velocity.thresholds",
        "sms-budget.sent-per-hour",
        "sms-budget.min-volume",
        "sms-budget.min-conversion",
      )
      assertTrue(tunables.forall(!keys.hasPath(_)))
    },
    test("the keys of an env.conf win, and the rest stay at their defaults") {
      for
        parsed <- events(deployment)
        tuning <- load(deployment, None, Config.Nested("antifraud", Config.Nested("tuning", deriveConfig[AntifraudTuningStore.Settings])))
        lists <- load(deployment, None, Config.Nested("antifraud", Config.Nested("lists", deriveConfig[AntifraudLists.Settings])))
        detectors <- load(deployment, None, Config.Nested("antifraud", Config.Nested("detectors", deriveConfig[AntifraudDetectors.Settings])))
      yield assertTrue(
        parsed.smsBudget == SmsBudget.Settings(prefixLength = 6, maxRanges = 500),
        parsed.decision.enabled,
        parsed.decision.timeout == 50.millis,
        parsed.decision.failureAction == DecisionAction.Allow,
        parsed.velocity.enabled,
        parsed.velocity.maxKeys == 1000,
        parsed.velocity.stripes == 4,
        parsed.velocity.flushInterval == 5.seconds,
        parsed.velocity.loadInterval == 20.seconds,
        tuning.refreshInterval == 2.seconds,
        lists.enabled,
        lists.maxTrafficShare == 0.1,
        lists.watchWeight == 20,
        detectors.enabled,
        detectors.interval == 30.seconds,
        detectors.statementTimeout == 3.seconds,
        detectors.fakeVerify.minFakeVerifies == 3,
        detectors.fakeVerify.window == 30.minutes,
      )
    },
    test("the overlay file is read, below the env.conf and above the defaults") {
      val overlay = Files.createTempFile("antifraud-overlay", ".conf")
      Files.writeString(overlay, "antifraud { retention = \"7 days\", batch-size = 500, stats-prefix-length = 5 }")
      (for
        parsed <- events("antifraud { batch-size = 250 }", Some(overlay.toFile))
      yield assertTrue(parsed.retention == 7.days, parsed.batchSize == 250, parsed.statsPrefixLength == 5, parsed.queueCapacity == 20000))
        .ensuring(ZIO.attempt(Files.deleteIfExists(overlay)).orDie)
    },
  )

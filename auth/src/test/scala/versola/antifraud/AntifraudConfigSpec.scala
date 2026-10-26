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

  private val sms: String =
    """antifraud {
      |  sms-budget { sent-per-hour = 200, prefix-length = 6 }
      |  decision { enabled = true, block-at = 100, failure-action = "allow" }
      |  velocity {
      |    enabled = true
      |    thresholds {
      |      phone { per-hour = 3, per-day = 5 }
      |      prefix { per-ten-minutes = 40, weight = 80 }
      |    }
      |  }
      |  lists { enabled = true, max-traffic-share = 0.1 }
      |  detectors { enabled = true, interval = "30 seconds", fake-verify { min-fake-verifies = 3 } }
      |}""".stripMargin

  def spec = suite("Antifraud config")(
    test("every key is there without any config, and nothing is on") {
      val all = AntifraudTestSettings.events
      assertTrue(
        all.queueCapacity == 20000,
        all.retention == 14.days,
        all.statsPrefixLength == 4,
        !all.smsBudget.enabled,
        !all.decision.enabled,
        all.decision.failureAction == DecisionAction.Challenge,
        all.decision.timeout == 20.millis,
        !all.velocity.enabled,
        all.velocity.thresholds.phone == AntifraudTestSettings.limit,
        all.velocity.thresholds.trustedDiscount == 40,
        !AntifraudTestSettings.lists.enabled,
        AntifraudTestSettings.lists.refreshInterval == 5.seconds,
        !AntifraudTestSettings.detectors.enabled,
        AntifraudTestSettings.detectors.prefixAnomaly.spikeFactor == 5.0,
        AntifraudTestSettings.detectors.fakeVerify.minFakeVerifies == 5,
      )
    },
    test("the keys of an env.conf win, and the rest stay at their defaults") {
      for
        parsed <- events(sms)
        lists <- load(sms, None, Config.Nested("antifraud", Config.Nested("lists", deriveConfig[AntifraudLists.Settings])))
        detectors <- load(sms, None, Config.Nested("antifraud", Config.Nested("detectors", deriveConfig[AntifraudDetectors.Settings])))
      yield assertTrue(
        parsed.smsBudget.sentPerHour == 200,
        parsed.smsBudget.prefixLength == 6,
        parsed.smsBudget.maxRanges == 10000,
        parsed.smsBudget.enabled,
        parsed.decision.enabled,
        parsed.decision.blockAt == 100,
        parsed.decision.delayAt == 0,
        parsed.decision.failureAction == DecisionAction.Allow,
        parsed.velocity.enabled,
        parsed.velocity.thresholds.phone.perHour == 3,
        parsed.velocity.thresholds.phone.perDay == 5,
        parsed.velocity.thresholds.phone.weight == 50,
        parsed.velocity.thresholds.prefix.perTenMinutes == 40,
        parsed.velocity.thresholds.prefix.weight == 80,
        parsed.velocity.thresholds.ip.weight == 20,
        lists.enabled,
        lists.maxTrafficShare == 0.1,
        lists.watchWeight == 20,
        detectors.enabled,
        detectors.interval == 30.seconds,
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

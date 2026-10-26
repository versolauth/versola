package versola.antifraud

import versola.util.http.VersolaApp
import zio.*
import zio.config.magnolia.deriveConfig

/** The settings as `versola-defaults.conf` has them (everything off), read the way `auth` reads them, for specs to
  * `copy` the keys they care about from. Case classes of settings have no defaults of their own. */
object AntifraudTestSettings:

  private def load[A](config: Config[A]): A =
    Unsafe.unsafe: unsafe ?=>
      Runtime.default.unsafe.run(AntifraudConfigSpec.load("", None, config)).getOrThrowFiberFailure()

  val events: AntifraudEvents.Settings = load(Config.Nested("antifraud", deriveConfig[AntifraudEvents.Settings]))
  val lists: AntifraudLists.Settings = load(Config.Nested("antifraud", Config.Nested("lists", deriveConfig[AntifraudLists.Settings])))
  val detectors: AntifraudDetectors.Settings =
    load(Config.Nested("antifraud", Config.Nested("detectors", deriveConfig[AntifraudDetectors.Settings])))

  val smsBudget: SmsBudget.Settings = events.smsBudget
  val decision: Antifraud.Settings = events.decision
  val velocity: VelocityCounters.Settings = events.velocity
  val thresholds: VelocityRules.Thresholds = events.velocity.thresholds

  /** A limit that is not set: no window limited, the weight a limit of a single key has in most specs. */
  val limit: VelocityRules.Limit = VelocityRules.Limit(perMinute = 0, perTenMinutes = 0, perHour = 0, perDay = 0, weight = 50)

  val velocityOff: VelocityCounters = VelocityCounters(velocity)

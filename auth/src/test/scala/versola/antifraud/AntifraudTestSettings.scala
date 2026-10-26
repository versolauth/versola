package versola.antifraud

import versola.util.http.VersolaApp
import zio.*
import zio.config.magnolia.deriveConfig

/** The settings as `versola-defaults.conf` has them (everything off), read the way `auth` reads them, for specs to
  * `copy` the keys they care about from, and the tuning as the first global row has it. Case classes of settings have no
  * defaults of their own. */
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

  /** Nothing limited or weighed: what applies until something is stored. */
  val tuning: Tuning = Tuning.off
  val thresholds: VelocityRules.Thresholds = tuning.thresholds

  /** A limit that is not set: no window limited, the weight a limit of a single key has in most specs. */
  val limit: VelocityRules.Limit = VelocityRules.Limit(perMinute = 0, perTenMinutes = 0, perHour = 0, perDay = 0, weight = 50)

  def fixed(tuning: Tuning): AntifraudTuning = AntifraudTuning.Fixed(tuning)

  def engine(decision: Antifraud.Settings, ruleSets: List[Antifraud.RuleSet], tuning: Tuning = tuning): Antifraud.Impl =
    Antifraud.Impl(decision, ruleSets, fixed(tuning))

  /** The velocity rules for the limits of `tuning`. */
  def velocityRules(velocity: VelocityCounters.Settings, tuning: Tuning): Antifraud.RuleSet =
    VelocityRules.ruleSet(velocity, fixed(tuning))

  val velocityOff: VelocityCounters = VelocityCounters(velocity, thresholds)

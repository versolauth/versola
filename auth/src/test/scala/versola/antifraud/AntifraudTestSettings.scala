package versola.antifraud

import zio.*

/** Settings for specs to `copy` the keys they care about from: everything off, as a deployment's `antifraud` block (see
  * `scripts/gen-env.scala`, which [[AntifraudConfigSpec]] reads) has it, and the constants of the components as they are
  * in the service. Case classes of settings have no defaults of their own. */
object AntifraudTestSettings:

  val events: AntifraudEvents.Settings = AntifraudEvents.Settings(
    retention = 14.days,
    statsInterval = 1.minute,
    statsRetention = 30.days,
    smsBudget = SmsBudget.Settings(prefixLength = 4),
    decision = Antifraud.Settings(enabled = false, timeout = 20.millis, failureAction = DecisionAction.Allow),
    velocity = VelocityCounters.Settings(enabled = false, flushInterval = 10.seconds, loadInterval = 20.seconds),
  )

  val lists: AntifraudLists.Settings = AntifraudLists.Settings(enabled = false, refreshInterval = 5.seconds, auditRetention = 365.days)

  val detectors: AntifraudDetectors.Settings = AntifraudDetectors.Settings(enabled = false, interval = 1.minute, retention = 90.days)

  val challenge: PowChallengeVerifier.Settings = PowChallengeVerifier.Settings(enabled = true, ttl = 2.minutes, maxAttempts = 5)

  val smsBudget: SmsBudget.Settings = events.smsBudget
  val decision: Antifraud.Settings = events.decision
  val velocity: VelocityCounters.Settings = events.velocity

  /** The sizes and limits the components have in the service. */
  val sizing: AntifraudEvents.Sizing = AntifraudEvents.Sizing.standard
  val smsSizing: SmsBudget.Sizing = SmsBudget.Sizing.standard
  val velocityLimits: VelocityCounters.Limits = VelocityCounters.Limits.standard
  val listsPolicy: AntifraudLists.Policy = AntifraudLists.Policy.standard
  val analytics: AntifraudDetectors.Analytics = AntifraudDetectors.Analytics.standard

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

  /** Counters with the service's limits, for settings of the specs. */
  def counters(settings: VelocityCounters.Settings, limits: VelocityCounters.Limits = velocityLimits): VelocityCounters =
    VelocityCounters(settings, limits, thresholds)

  val velocityOff: VelocityCounters = counters(velocity)

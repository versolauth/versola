package versola.antifraud

import zio.json.*

/** What an operator changes while an attack is going on: the thresholds the score turns into an action at, the weights
  * of the rules, which rules only record, the limits on codes sent to a range of numbers and the difficulty
  * of a proof of work. Not deployment configuration, because a restart is too slow for it and empties the in-memory
  * counters; it is stored in `af_settings` (see [[AntifraudTuningStore]]) and changed through the admin API.
  *
  * @param challengeAt    score from which a step is challenged; 0 disables the threshold (likewise `blockAt`)
  * @param blockAt        score from which a step is refused
  * @param pow            how much work a proof-of-work challenge asks (see [[PowChallengeVerifier]])
  * @param thresholds     the limits (and weights) of the velocity rules
  * @param smsLimits      the limits on codes sent to a range of numbers
  * @param ruleWeights    the weight of a rule by its name, in place of the one it has; a rule that forces an action is not
  *                       weighed
  * @param shadowRules    rules that are evaluated and listed in the fired rules as `shadow:<name>`, but add no weight
  *                       and force nothing: a dry run before the rule is made real
  */
case class Tuning(
    challengeAt: Int,
    blockAt: Int,
    pow: PowDifficulty,
    thresholds: VelocityRules.Thresholds,
    smsLimits: SmsLimits,
    ruleWeights: Map[String, Int],
    shadowRules: Set[String],
) derives JsonCodec

/** Zero bits asked of a request that only just reached the challenge score (`minDifficulty`), of any request at most
  * (`maxDifficulty`: each bit doubles the work, and a phone has to do it), and the score above `challengeAt` that adds one
  * bit (`scoreStep`; 0 adds none). */
case class PowDifficulty(minDifficulty: Int, maxDifficulty: Int, scoreStep: Int) derives JsonCodec

/** The limit on codes sent to one range of numbers, see [[SmsBudget]]. 0 is no limit. */
case class SmsLimit(sentPerHour: Int, minVolume: Int, minConversion: Double) derives JsonCodec:
  def enabled: Boolean = sentPerHour > 0 || minVolume > 0

/** `byPrefix` is keyed by the leading digits of a number, country code included (`"7701"`): the longest key a number
  * starts with decides, and `default` applies to a number that starts with none. */
case class SmsLimits(default: SmsLimit, byPrefix: Map[String, SmsLimit]) derives JsonCodec:
  def of(prefix: String): SmsLimit =
    byPrefix.keysIterator.filter(prefix.startsWith).maxByOption(_.length).fold(default)(byPrefix)

  def enabled: Boolean = default.enabled || byPrefix.values.exists(_.enabled)

object Tuning:
  /** Nothing limited or weighed: what applies until the stored settings are read, and the values the first
    * global row is seeded with (migration `V0024`). */
  val off: Tuning = Tuning(
    challengeAt = 0,
    blockAt = 0,
    pow = PowDifficulty(minDifficulty = 14, maxDifficulty = 18, scoreStep = 15),
    thresholds = VelocityRules.Thresholds(
      phone = VelocityRules.Limit(perMinute = 0, perTenMinutes = 0, perHour = 0, perDay = 0, weight = 50),
      ip = VelocityRules.Limit(perMinute = 0, perTenMinutes = 0, perHour = 0, perDay = 0, weight = 20),
      device = VelocityRules.Limit(perMinute = 0, perTenMinutes = 0, perHour = 0, perDay = 0, weight = 60),
      asn = VelocityRules.Limit(perMinute = 0, perTenMinutes = 0, perHour = 0, perDay = 0, weight = 20),
      prefix = VelocityRules.Limit(perMinute = 0, perTenMinutes = 0, perHour = 0, perDay = 0, weight = 30),
      trustedDiscount = 40,
    ),
    smsLimits = SmsLimits(default = SmsLimit(sentPerHour = 0, minVolume = 0, minConversion = 0.1), byPrefix = Map.empty),
    ruleWeights = Map.empty,
    shadowRules = Set.empty,
  )

/** The tuning the rules and the budget read, from memory.
  *
  * Where it comes from is the store's business ([[AntifraudTuningStore]]); this is what the engine, the counters, the budget
  * and the challenge are given. One tuning applies to the whole deployment. */
trait AntifraudTuning:
  /** What applies. Before the tuning has been read once, [[Tuning.off]]. */
  def current: Tuning

  /** False until the tuning has been read once. A request is then decided as `failure-action` says: nothing says what
    * the thresholds are, and they must not be taken to be 0. */
  def available: Boolean

object AntifraudTuning:
  /** A tuning that is never stored: `tuning` applies. */
  final class Fixed(tuning: Tuning) extends AntifraudTuning:
    override def current: Tuning = tuning

    override def available: Boolean = true

  /** Everything off, for a deployment or a test that has no store. */
  val off: AntifraudTuning = Fixed(Tuning.off)

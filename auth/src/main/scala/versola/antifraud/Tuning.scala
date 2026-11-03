package versola.antifraud

import zio.json.*
import zio.json.ast.Json

/** What an operator changes while an attack is going on: the thresholds the score turns into an action at, the weights
  * of the rules, which rules only record, the limits on codes sent to a range of numbers and the difficulty
  * of a proof of work. Not deployment configuration, because a restart is too slow for it and empties the in-memory
  * counters; it is stored in `af_settings` (see [[AntifraudTuningStore]]) and changed through the admin API.
  *
  * Every field has a default, and the codec reads a stored row that lacks a key as having the default: a key added in a
  * later version must not make the row of an older one unreadable (the service would then start in `failure-action`).
  * What a missing key means is always the safe value: nothing limited, nothing weighed, and `shadow`. A key the codec does not
  * know is the opposite case and is refused (`@jsonNoExtraFields`, here and on every nested class): a typo in a `PUT` must not be
  * dropped and answered with 200, so a stored row with an unknown key fails to decode as well, except the keys earlier builds
  * wrote and nothing reads now ([[Tuning.storedDecoder]]).
  *
  * @param mode           `shadow` computes the whole decision (score thresholds, forced list actions and the SMS budget)
  *                       and records what it would have done, but lets every request through; `enforce` acts on it. The
  *                       per-entry `shadow` flag of the lists and `shadowRules` stay as finer knobs inside `enforce`
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
@jsonNoExtraFields
case class Tuning(
    mode: TuningMode = TuningMode.shadow,
    challengeAt: Int = 0,
    blockAt: Int = 0,
    pow: PowDifficulty = PowDifficulty(),
    thresholds: VelocityRules.Thresholds = VelocityRules.Thresholds(),
    smsLimits: SmsLimits = SmsLimits(),
    ruleWeights: Map[String, Int] = Map.empty,
    shadowRules: Set[String] = Set.empty,
) derives JsonCodec:
  def enforcing: Boolean = mode == TuningMode.enforce

/** Whether the decision is acted on. */
enum TuningMode derives JsonCodec:
  /** The decision is computed and recorded (`would_action`), and every request is let through. */
  case shadow

  /** The decision is acted on. */
  case enforce

  def label: String = toString

/** Zero bits asked of a request that only just reached the challenge score (`minDifficulty`), of any request at most
  * (`maxDifficulty`: each bit doubles the work, and a phone has to do it), and the score above `challengeAt` that adds one
  * bit (`scoreStep`; 0 adds none). */
@jsonNoExtraFields
case class PowDifficulty(minDifficulty: Int = 14, maxDifficulty: Int = 18, scoreStep: Int = 15) derives JsonCodec

/** The limit on codes sent to one range of numbers, see [[SmsBudget]]. 0 is no limit. */
@jsonNoExtraFields
case class SmsLimit(sentPerHour: Int = 0, minVolume: Int = 0, minConversion: Double = 0.1) derives JsonCodec:
  def enabled: Boolean = sentPerHour > 0 || minVolume > 0

/** `byPrefix` is keyed by the leading digits of a number, country code included (`"7701"`): the longest key a number
  * starts with decides, and `default` applies to a number that starts with none. */
@jsonNoExtraFields
case class SmsLimits(default: SmsLimit = SmsLimit(), byPrefix: Map[String, SmsLimit] = Map.empty) derives JsonCodec:
  def of(prefix: String): SmsLimit =
    byPrefix.keysIterator.filter(prefix.startsWith).maxByOption(_.length).fold(default)(byPrefix)

  def enabled: Boolean = default.enabled || byPrefix.values.exists(_.enabled)

object Tuning:
  /** Keys that earlier builds wrote into the stored row and no build reads any more (the Delay step and the fake-send wait). */
  private val retiredKeys = Set("delayAt", "delayMillis", "fakeSendDelayMillis")

  /** How the stored row is read: the codec, which refuses a key it does not know, after the retired keys of rows written by
    * earlier builds are dropped. A request body is read with the codec itself, so a `PUT` naming one of them is refused as any
    * other unknown key. */
  val storedDecoder: JsonDecoder[Tuning] = JsonDecoder[Json.Obj].mapOrFail: row =>
    // Through the text, not the AST, so that a value that is wrong is reported as the codec reports it everywhere else.
    Json.Obj(row.fields.filterNot((name, _) => retiredKeys.contains(name))).toJson.fromJson[Tuning]

  /** Nothing limited or weighed, and in `shadow`: what applies until the stored settings are read, where there is no
    * row, and the first row auth's bootstrap writes. */
  val off: Tuning = Tuning()

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

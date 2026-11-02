package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import zio.*
import zio.config.magnolia.DeriveConfig

/** What the rules get to see about one risky step: who asks, for what, from where. Every field but the action,
  * tenant and client is optional, because not every signal exists on every path (no device id before P1-1, no ASN
  * before P1-3); a caller says so explicitly with `None`.
  *
  * Destinations are never carried as themselves: [[subjectHash]] is the keyed hash the event log uses.
  *
  * @param signals extra named facts a rule may read, filled by the hooks of later tasks (counters, lists, ...)
  */
case class AntifraudContext(
    tenantId: Option[TenantId],
    clientId: ClientId,
    action: AntifraudAction,
    subjectHash: Option[String],
    destPrefix: Option[String],
    deviceId: Option[String],
    ip: Option[String],
    asn: Option[Int],
    userAgent: Option[String],
    ja4: Option[String],
    signals: Map[String, String],
)

/** What to do with the step, from the mildest to the harshest. */
enum DecisionAction:
  /** Go on. */
  case Allow

  /** Ask for proof first (a proof-of-work or a captcha token, P1-2). */
  case Challenge

  /** Answer as if the step succeeded and do nothing (P0-6). */
  case Fake

  /** Refuse. */
  case Block

object DecisionAction:
  /** Read from the config by name, in any case: `failure-action = "challenge"`. */
  given DeriveConfig[DecisionAction] = DeriveConfig[String].mapOrFail: name =>
    DecisionAction.values
      .find(_.toString.equalsIgnoreCase(name))
      .toRight(Config.Error.InvalidData(message = s"expected one of ${DecisionAction.values.mkString(", ")}, got $name"))

/** @param action      what is done with the step. In `shadow` mode ([[TuningMode]]) it is always `Allow`
  * @param score       the sum of the weights of the fired rules
  * @param firedRules  names of the rules that matched, for the event log and for explaining a decision
  * @param wouldAction what the engine decided, whatever the mode: equal to `action` when enforcing, and what `action`
  *                    would have been in `shadow`
  */
case class Decision(action: DecisionAction, score: Int, firedRules: List[String], wouldAction: DecisionAction):
  /** Something other than a plain pass happened or would have happened. */
  def noteworthy: Boolean = action != DecisionAction.Allow || wouldAction != DecisionAction.Allow

object Decision:
  val allow: Decision = Decision(DecisionAction.Allow, 0, Nil, DecisionAction.Allow)

  /** A decision that is acted on as made. */
  def apply(action: DecisionAction, score: Int, firedRules: List[String]): Decision =
    Decision(action, score, firedRules, action)

/** One condition with a weight. A rule that `forces` an action decides it alone (a deny list entry blocks whatever
  * else is true); the others only add their weight to the score, and the thresholds turn the score into an action.
  * `test` must be cheap and must not block: it runs on the request.
  */
case class AntifraudRule(
    name: String,
    weight: Int,
    forces: Option[DecisionAction],
    test: AntifraudContext => Boolean,
)

trait Antifraud:
  /** Decides what to do with a risky step. Never fails and never takes longer than the configured timeout: a
    * rule that throws, or an evaluation that is too slow, gives [[Antifraud.Settings.failureAction]] (extra friction,
    * not a refusal) and the rule name `antifraud_error`. With antifraud off the answer is always [[Decision.allow]]
    * and no rule is run. In `shadow` mode the decision is computed in full and acted on as `Allow` (see [[Decision]]). */
  def evaluate(ctx: AntifraudContext): UIO[Decision]

object Antifraud:

  /** @param enabled        off by default: nothing changes for a deployment that does not turn it on
    * The thresholds and weights are not here: they are tuned during an attack (see [[AntifraudTuning]]).
    *
    * @param timeout        the longest an evaluation may take
    * @param failureAction  the decision when evaluation fails or times out
    * @param fakeSendDelay  how long a fake send waits while no real send has been timed yet (after a start, before the
    *                       first code goes out); from then on it waits as long as a recent real one took
    */
  case class Settings(
      enabled: Boolean,
      timeout: Duration,
      failureAction: DecisionAction,
      fakeSendDelay: Duration,
  )

  /** The engine over the rules of the components that add them: the lists and the velocity limits. A component that adds
    * rules is added here, where the engine is, not in the application's wiring. */
  val live: URLayer[AntifraudEvents.Settings & AntifraudTuning & AntifraudLists, Antifraud] =
    ZLayer.fromFunction: (settings: AntifraudEvents.Settings, tuning: AntifraudTuning, lists: AntifraudLists) =>
      Impl(settings.decision, List(lists.ruleSet, VelocityRules.ruleSet(settings.velocity, tuning)), tuning): Antifraud

  /** The rule a decision made of a failed evaluation names. */
  val ErrorRule = "antifraud_error"

  /** The rule a decision made while the tuning has not been read names. */
  val TuningUnavailableRule = "antifraud_tuning_unavailable"

  /** Names in a decision's fired rules that are not rules: a failure stands in for the evaluation. */
  val failureRules: Set[String] = Set(ErrorRule, TuningUnavailableRule)

  /** Why an evaluation did not produce a decision (the `reason` of `antifraud_evaluation_failed_total`). */
  enum Failure(val reason: String, val rule: String):
    case Timeout extends Failure("timeout", ErrorRule)
    case Error extends Failure("error", ErrorRule)
    case TuningUnavailable extends Failure("tuning_unavailable", TuningUnavailableRule)

  /** The decision of a failed evaluation: `failure-action`. While `shadow` is known to be on it is only recorded, not
    * acted on, like any other decision; while the tuning is not known (it has not been read) the mode is not either, and
    * `failure-action` applies as it is. */
  def failed(settings: Settings, tuning: AntifraudTuning, failure: Failure): Decision =
    val acting = failure == Failure.TuningUnavailable || tuning.current.enforcing
    Decision(if acting then settings.failureAction else DecisionAction.Allow, 0, List(failure.rule), settings.failureAction)

  private val velocityRuleNames: List[String] =
    (for
      key <- VelocityKey.values.toList
      window <- VelocityWindow.values.toList
    yield VelocityRules.ruleName(key, window)) :+ "velocity_known_user"

  private val listRuleNames: List[String] =
    for
      valueType <- AntifraudValueType.values.toList
      kind <- List("deny_list", "fake_list", "allow_list", "shadow_list", "watch_list")
    yield s"$kind:$valueType"

  /** The names of the rules there are, which `ruleWeights` and `shadowRules` of the tuning may name: the lists' (one rule
    * per kind of list and of value) and the velocity ones (one per key and window). A name that is not here would
    * never match, which is what a typo looks like; the settings are refused for it. */
  val ruleNames: Set[String] = (velocityRuleNames ++ listRuleNames).toSet

  val off: Antifraud = _ => ZIO.succeed(Decision.allow)

  /** Source of the rules, so that components (lists, counters, destination controls) each add theirs without the
    * engine knowing them. */
  trait RuleSet:
    def rules: UIO[List[AntifraudRule]]

    /** The rules for this request; a source whose rules depend on the tenant overrides it. */
    def rulesFor(ctx: AntifraudContext): UIO[List[AntifraudRule]] = rules

  /** `settings` has what a deployment sets (the switch, the timeout, the action on failure); what is tuned during an
    * attack (the mode, the thresholds, the weights, the rules that only record) is `tuning`'s. */
  class Impl(settings: Settings, ruleSets: List[RuleSet], tuning: AntifraudTuning) extends Antifraud:
    override def evaluate(ctx: AntifraudContext): UIO[Decision] =
      if !settings.enabled then ZIO.succeed(Decision.allow)
      // Nothing says what the thresholds are, and they must not be taken to be 0.
      else if !tuning.available then fail(Failure.TuningUnavailable)
      else
        ZIO.foreach(ruleSets)(_.rulesFor(ctx)).map(_.flatten)
          .map(decide(ctx, _))
          .timeout(settings.timeout)
          .flatMap:
            case Some(decision) => ZIO.succeed(decision)
            case None => fail(Failure.Timeout)
          .catchAllCause(_ => fail(Failure.Error))

    private def fail(failure: Failure): UIO[Decision] =
      AntifraudMetrics.evaluationFailed(failure.reason).as(failed(settings, tuning, failure))

    private[antifraud] def decide(ctx: AntifraudContext, rules: List[AntifraudRule]): Decision =
      val t = tuning.current
      val (shadow, fired) = rules.filter(_.test(ctx)).partition(rule => t.shadowRules.contains(rule.name))
      // A rule that forces an action decides alone; its weight is not what it does.
      val score = fired.map(rule => if rule.forces.isDefined then rule.weight else t.ruleWeights.getOrElse(rule.name, rule.weight)).sum
      val names = fired.map(_.name) ++ shadow.map(rule => s"$ShadowPrefix${rule.name}")
      val forced = fired.flatMap(_.forces).maxByOption(_.ordinal)
      val byScore =
        if t.blockAt > 0 && score >= t.blockAt then DecisionAction.Block
        else if t.challengeAt > 0 && score >= t.challengeAt then DecisionAction.Challenge
        else DecisionAction.Allow
      val would = Seq(Some(byScore), forced).flatten.maxBy(_.ordinal)
      // In shadow the whole decision is made, and none of it is acted on.
      Decision(if t.enforcing then would else DecisionAction.Allow, score, names, would)

  /** What a rule that only records is listed as in a decision's fired rules. */
  val ShadowPrefix = "shadow:"

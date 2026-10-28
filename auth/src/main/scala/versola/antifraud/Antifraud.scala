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

  /** Go on or refuse as configured, and put it in front of a person. */
  case Review

object DecisionAction:
  /** Read from the config by name, in any case: `failure-action = "challenge"`. */
  given DeriveConfig[DecisionAction] = DeriveConfig[String].mapOrFail: name =>
    DecisionAction.values
      .find(_.toString.equalsIgnoreCase(name))
      .toRight(Config.Error.InvalidData(message = s"expected one of ${DecisionAction.values.mkString(", ")}, got $name"))

/** @param score      the sum of the weights of the fired rules
  * @param firedRules names of the rules that matched, for the event log and for explaining a decision
  */
case class Decision(action: DecisionAction, score: Int, firedRules: List[String])

object Decision:
  val allow: Decision = Decision(DecisionAction.Allow, 0, Nil)

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
    * and no rule is run. */
  def evaluate(ctx: AntifraudContext): UIO[Decision]

object Antifraud:

  /** @param enabled        off by default: nothing changes for a deployment that does not turn it on
    * The thresholds and weights are not here: they are tuned during an attack (see [[AntifraudTuning]]).
    *
    * @param timeout        the longest an evaluation may take
    * @param failureAction  the decision when evaluation fails or times out
    */
  case class Settings(
      enabled: Boolean,
      timeout: Duration,
      failureAction: DecisionAction,
  )

  /** The engine over the rules the given sources supply; each component that adds rules is listed where the layer
    * is assembled. */
  def live(ruleSets: List[RuleSet]): URLayer[AntifraudEvents.Settings & AntifraudTuning, Antifraud] =
    ZLayer.fromFunction((settings: AntifraudEvents.Settings, tuning: AntifraudTuning) => Impl(settings.decision, ruleSets, tuning): Antifraud)

  /** The rule a decision made of a failed evaluation names. */
  val ErrorRule = "antifraud_error"

  val off: Antifraud = _ => ZIO.succeed(Decision.allow)

  /** Source of the rules, so that components (lists, counters, destination controls) each add theirs without the
    * engine knowing them. */
  trait RuleSet:
    def rules: UIO[List[AntifraudRule]]

    /** The rules for this request; a source whose rules depend on the tenant overrides it. */
    def rulesFor(ctx: AntifraudContext): UIO[List[AntifraudRule]] = rules

  /** `settings` has what a deployment sets (the switch, the timeout, the action on failure); what is tuned during an
    * attack (the thresholds, the weights, the rules that only record) is `tuning`'s, per tenant. */
  class Impl(settings: Settings, ruleSets: List[RuleSet], tuning: AntifraudTuning) extends Antifraud:
    override def evaluate(ctx: AntifraudContext): UIO[Decision] =
      if !settings.enabled then ZIO.succeed(Decision.allow)
      // Nothing says what the thresholds are, and they must not be taken to be 0.
      else if !tuning.available then ZIO.succeed(Decision(settings.failureAction, 0, List("antifraud_tuning_unavailable")))
      else
        ZIO.foreach(ruleSets)(_.rulesFor(ctx)).map(_.flatten)
          .map(decide(ctx, _))
          .timeoutTo(failed)(identity)(settings.timeout)
          .catchAllCause(_ => ZIO.succeed(failed))

    private def failed: Decision = Decision(settings.failureAction, 0, List(ErrorRule))

    private[antifraud] def decide(ctx: AntifraudContext, rules: List[AntifraudRule]): Decision =
      val t = tuning.current
      val (shadow, fired) = rules.filter(_.test(ctx)).partition(rule => t.shadowRules.contains(rule.name))
      // A rule that forces an action decides alone; its weight is not what it does.
      val score = fired.map(rule => if rule.forces.isDefined then rule.weight else t.ruleWeights.getOrElse(rule.name, rule.weight)).sum
      val names = fired.map(_.name) ++ shadow.map(rule => s"shadow:${rule.name}")
      val forced = fired.flatMap(_.forces).maxByOption(_.ordinal)
      val byScore =
        if t.blockAt > 0 && score >= t.blockAt then DecisionAction.Block
        else if t.reviewAt > 0 && score >= t.reviewAt then DecisionAction.Review
        else if t.challengeAt > 0 && score >= t.challengeAt then DecisionAction.Challenge
        else DecisionAction.Allow
      Decision(Seq(Some(byScore), forced).flatten.maxBy(_.ordinal), score, names)

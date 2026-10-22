package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}
import zio.*

/** What the rules get to see about one risky step: who asks, for what, from where. Everything but the action is
  * optional, because not every signal exists on every path (no device id before P1-1, no ASN before P1-3).
  *
  * Destinations are never carried as themselves: [[subjectHash]] is the keyed hash the event log uses.
  *
  * @param signals extra named facts a rule may read, filled by the hooks of later tasks (counters, lists, ...)
  */
case class AntifraudContext(
    tenantId: Option[TenantId],
    clientId: ClientId,
    action: AntifraudAction,
    subjectHash: Option[String] = None,
    destPrefix: Option[String] = None,
    deviceId: Option[String] = None,
    ip: Option[String] = None,
    asn: Option[Int] = None,
    userAgent: Option[String] = None,
    ja4: Option[String] = None,
    signals: Map[String, String] = Map.empty,
)

/** What to do with the step, from the mildest to the harshest. */
enum DecisionAction:
  /** Go on. */
  case Allow
  /** Ask for proof first (a proof-of-work or a captcha token, P1-2). */
  case Challenge
  /** Go on, slowly: the answer is held back (a tarpit). */
  case Delay
  /** Answer as if the step succeeded and do nothing (P0-6). */
  case Fake
  /** Refuse. */
  case Block
  /** Go on or refuse as configured, and put it in front of a person. */
  case Review

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
    * @param delayAt        score from which the step is delayed; 0 disables the threshold
    * @param challengeAt    score from which the step is challenged; 0 disables
    * @param reviewAt       score from which the step is sent to review; 0 disables
    * @param blockAt        score from which the step is refused; 0 disables
    * @param timeout        the longest an evaluation may take
    * @param failureAction  the decision when evaluation fails or times out
    */
  case class Settings(
      enabled: Boolean = false,
      delayAt: Int = 0,
      challengeAt: Int = 0,
      reviewAt: Int = 0,
      blockAt: Int = 0,
      timeout: Duration = 20.millis,
      failureAction: DecisionAction = DecisionAction.Challenge,
  )

  val off: Antifraud = _ => ZIO.succeed(Decision.allow)

  /** Source of the rules, so that components (lists, counters, destination controls) each add theirs without the
    * engine knowing them. */
  trait RuleSet:
    def rules: UIO[List[AntifraudRule]]

  class Impl(settings: Settings, ruleSets: List[RuleSet]) extends Antifraud:
    override def evaluate(ctx: AntifraudContext): UIO[Decision] =
      if !settings.enabled then ZIO.succeed(Decision.allow)
      else
        ZIO.foreach(ruleSets)(_.rules).map(_.flatten)
          .map(decide(ctx, _))
          .timeoutTo(failed)(identity)(settings.timeout)
          .catchAllCause(_ => ZIO.succeed(failed))

    private def failed: Decision = Decision(settings.failureAction, 0, List("antifraud_error"))

    private[antifraud] def decide(ctx: AntifraudContext, rules: List[AntifraudRule]): Decision =
      val fired = rules.filter(_.test(ctx))
      val score = fired.map(_.weight).sum
      val names = fired.map(_.name)
      val forced = fired.flatMap(_.forces).maxByOption(_.ordinal)
      val byScore =
        if settings.blockAt > 0 && score >= settings.blockAt then DecisionAction.Block
        else if settings.reviewAt > 0 && score >= settings.reviewAt then DecisionAction.Review
        else if settings.challengeAt > 0 && score >= settings.challengeAt then DecisionAction.Challenge
        else if settings.delayAt > 0 && score >= settings.delayAt then DecisionAction.Delay
        else DecisionAction.Allow
      Decision(Seq(Some(byScore), forced).flatten.maxBy(_.ordinal), score, names)

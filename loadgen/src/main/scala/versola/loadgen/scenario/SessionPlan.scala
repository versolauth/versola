package versola.loadgen.scenario

import versola.loadgen.config.SessionConfig
import versola.loadgen.model.Platform
import versola.loadgen.scheduler.{ActionCount, RandomSource}

/** The shape one session will take, drawn before its first hop (design doc §2.3, dev spec §7.4).
  *
  * Every probability in §2.3 is stated *per session* -- "one action is an L2 payment, p = 0.25
  * per session", "session longer than the access-token TTL, p = 0.25", "explicit logout, p =
  * 0.05" -- so each is drawn exactly once, here, rather than re-flipped inside the action loop.
  * A coin flipped per action would turn each of them into a per-action rate and multiply the
  * step-up and logout volume by the session length, which is the load being generated changing
  * silently while the configured numbers stay the same. §2.3 also calls step-ups "the load source
  * everyone forgets", so overshooting there is not a rounding error.
  *
  * @param paymentAction
  *   index of the one action that requires L2, or `None` for a session with no payment in it.
  *   Never index 0: that slot is the `GET /accounts` the app issues on open
  *   ([[ActionCount.mandatoryActions]]), which is machine-driven and cannot be the user's payment.
  * @param extraRefresh
  *   whether this session outlives its access token and refreshes mid-run. Whether it *actually*
  *   refreshes also depends on the session reaching `session.access-token-ttl`, which the runner
  *   checks against the clock -- a two-action session that drew `true` simply never gets there.
  */
final case class SessionPlan(
    actionCount: Int,
    paymentAction: Option[Int],
    extraRefresh: Boolean,
    logout: Boolean,
)

object SessionPlan:

  def draw(config: SessionConfig, platform: Platform, random: RandomSource): SessionPlan =
    val actionCount = ActionCount.sample(config.actionCount, platform, random)
    SessionPlan(
      actionCount = actionCount,
      paymentAction = drawPaymentAction(config, actionCount, random),
      extraRefresh = random.nextDouble() < config.extraRefreshProbability,
      logout = random.nextDouble() < logoutProbability(config, platform),
    )

  /** §2.3's "has a live credential?" branch: a session resumes when one is available and the
    * full-login draw does not pre-empt it.
    *
    * `hasLiveSession` gates the draw rather than being folded into it, because the configured
    * probability is conditional on there being something to resume: `full-login-probability` is
    * 0.033 for mobile, and treating a user with no live session as a 96.7% resume would leave
    * that session unable to start at all.
    */
  def startsWithFullLogin(
      config: SessionConfig,
      platform: Platform,
      hasLiveSession: Boolean,
      random: RandomSource,
  ): Boolean =
    !hasLiveSession || random.nextDouble() < fullLoginProbability(config, platform)

  def fullLoginProbability(config: SessionConfig, platform: Platform): Double =
    platform match
      case Platform.Mobile => config.fullLoginProbability.mobile
      case Platform.Web => config.fullLoginProbability.web

  def logoutProbability(config: SessionConfig, platform: Platform): Double =
    platform match
      case Platform.Mobile => config.logoutProbability.mobile
      case Platform.Web => config.logoutProbability.web

  /** Uniform over the user-driven slots only. A session of exactly one action has no slot to put
    * a payment in -- the only action it makes is the app's own opening call -- so it draws none
    * rather than displacing it, which would both contradict design doc §3's "the first call of
    * every session" and quietly raise the step-up rate on the shortest sessions.
    */
  private def drawPaymentAction(config: SessionConfig, actionCount: Int, random: RandomSource): Option[Int] =
    val userDriven = actionCount - ActionCount.mandatoryActions
    if userDriven <= 0 || random.nextDouble() >= config.paymentProbability then None
    else Some(ActionCount.mandatoryActions + (random.nextDouble() * userDriven).toInt)

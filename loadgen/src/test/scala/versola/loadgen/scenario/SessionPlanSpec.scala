package versola.loadgen.scenario

import versola.loadgen.config.*
import versola.loadgen.model.Platform
import versola.loadgen.scheduler.{ActionCount, RandomSource}
import zio.test.*
import zio.{Duration, durationInt}

/** The per-session draws of design doc §2.3, which decide how much step-up and logout traffic a
  * campaign actually produces.
  */
object SessionPlanSpec extends ZIOSpecDefault:

  private def config(
      paymentProbability: Double = 0.25,
      extraRefreshProbability: Double = 0.25,
      logoutMobile: Double = 0.05,
      logoutWeb: Double = 0.4,
      fullLoginMobile: Double = 0.033,
      mobileMean: Double = 5.0,
  ): SessionConfig =
    SessionConfig(
      fullLoginProbability = FullLoginProbabilityConfig(fullLoginMobile, 0.4),
      actionCount = ActionCountConfig(mobileMean, 9.0, 0.6),
      thinkTime = ThinkTimeConfig(4.seconds, 0.8),
      paymentProbability = paymentProbability,
      extraRefreshProbability = extraRefreshProbability,
      logoutProbability = LogoutProbabilityConfig(logoutMobile, logoutWeb),
      accessTokenTtl = 15.minutes,
      refreshTokenTtl = Duration.fromSeconds(30L * 24 * 3600),
    )

  private def plans(settings: SessionConfig, platform: Platform, count: Int): Vector[SessionPlan] =
    val random = RandomSource.seeded(20260315L)
    Vector.fill(count)(SessionPlan.draw(settings, platform, random))

  def spec = suite("SessionPlan")(
    test("the payment never lands on the opening call, whatever the session length") {
      val drawn = plans(config(paymentProbability = 1.0), Platform.Mobile, 5000)
      assertTrue(
        drawn.forall(plan => plan.paymentAction.forall(_ >= ActionCount.mandatoryActions)),
        drawn.forall(plan => plan.paymentAction.forall(_ < plan.actionCount)),
        // A session of only the opening call has no slot for one rather than displacing it.
        drawn.filter(_.actionCount == ActionCount.mandatoryActions).forall(_.paymentAction.isEmpty),
      )
    },
    // The bug this guards is a coin flipped per action instead of per session, which multiplies
    // step-up volume by the session length while the configured 0.25 stays where it was.
    test("the payment rate is the configured per-session probability, not a per-action one") {
      val drawn = plans(config(paymentProbability = 0.25), Platform.Mobile, 20000)
      val eligible = drawn.count(_.actionCount > ActionCount.mandatoryActions)
      val withPayment = drawn.count(_.paymentAction.isDefined)
      assertTrue(math.abs(withPayment.toDouble / eligible - 0.25) < 0.02)
    },
    test("at most one action per session is the payment") {
      val drawn = plans(config(paymentProbability = 1.0), Platform.Mobile, 1000)
      assertTrue(drawn.forall(_.paymentAction.forall(_ => true)), drawn.forall(_.paymentAction.size <= 1))
    },
    test("logout is drawn per platform, so web sessions end explicitly far more often") {
      val mobile = plans(config(), Platform.Mobile, 20000).count(_.logout)
      val web = plans(config(), Platform.Web, 20000).count(_.logout)
      assertTrue(
        math.abs(mobile.toDouble / 20000 - 0.05) < 0.01,
        math.abs(web.toDouble / 20000 - 0.4) < 0.02,
      )
    },
    test("the extra refresh is drawn at the configured rate") {
      val drawn = plans(config(extraRefreshProbability = 0.25), Platform.Mobile, 20000)
      assertTrue(math.abs(drawn.count(_.extraRefresh).toDouble / 20000 - 0.25) < 0.02)
    },
    test("a probability of zero draws nothing and one draws everything") {
      val never = plans(config(paymentProbability = 0.0, extraRefreshProbability = 0.0, logoutMobile = 0.0), Platform.Mobile, 500)
      val always = plans(config(paymentProbability = 1.0, extraRefreshProbability = 1.0, logoutMobile = 1.0), Platform.Mobile, 500)
      assertTrue(
        never.forall(plan => plan.paymentAction.isEmpty && !plan.extraRefresh && !plan.logout),
        always.forall(plan => plan.extraRefresh && plan.logout),
      )
    },
    // §2.3's "has a live credential?" branch. The conditional shape is the point: 0.033 is the
    // chance a user *with* a resumable session logs in fully anyway, not the chance any session
    // starts with a login.
    test("a user with no live session always logs in, whatever the full-login probability") {
      val random = RandomSource.seeded(11L)
      val settings = config(fullLoginMobile = 0.0)
      assertTrue(
        (1 to 200).forall(_ => SessionPlan.startsWithFullLogin(settings, Platform.Mobile, false, random)),
      )
    },
    test("a user with a live session logs in fully at the configured rate") {
      val random = RandomSource.seeded(12L)
      val settings = config(fullLoginMobile = 0.033)
      val full = (1 to 20000).count(_ => SessionPlan.startsWithFullLogin(settings, Platform.Mobile, true, random))
      assertTrue(math.abs(full.toDouble / 20000 - 0.033) < 0.01)
    },
  )

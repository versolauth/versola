package versola.loadgen.scenario

import versola.loadgen.config.{LoadgenConfig, LoadgenConfigSpec, SessionConfig}
import versola.loadgen.model.Platform
import versola.loadgen.scheduler.RandomSource
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*

/** The refresh:code ratio the shipped session profile produces (versolauth/versola#591: the target is 5-10, the
  * default aims at 8).
  *
  * Counted the way the report counts it (`token-refresh` over `token-code` steps): a mobile arrival that resumes
  * is one refresh and one that logs in is one code exchange; a payment action is a step-up, which is another code
  * exchange; each planned refresh is one more refresh. The TTL-gated extra refresh is left out because a session
  * lasts about a minute against an access token of at least 15 minutes. Web arrivals have neither step (edge
  * completes the login behind the cookie), so mobile sessions alone decide the ratio.
  */
object RefreshRatioSpec extends ZIOSpecDefault:

  private def load(source: String) =
    TypesafeConfigProvider.fromHoconString(source).kebabCase.load(deriveConfig[LoadgenConfig])

  private def ratio(session: SessionConfig, sessions: Int, seed: Long): Double =
    val random = RandomSource.seeded(seed)
    var refreshes = 0L
    var codes = 0L
    var i = 0
    while i < sessions do
      // Steady state: the user has a live session, so the full-login draw is the only thing that logs in.
      val login = SessionPlan.startsWithFullLogin(session, Platform.Mobile, true, random)
      val plan = SessionPlan.draw(session, Platform.Mobile, random)
      if login then codes += 1 else refreshes += 1
      if plan.paymentAction.isDefined then codes += 1
      refreshes += plan.refreshesBefore.sum
      i += 1
    refreshes.toDouble / codes

  def spec = suite("refresh:code ratio of the shipped session profile")(
    test("the default session profile draws about 8 refresh exchanges per code exchange") {
      for config <- load(LoadgenConfigSpec.hocon)
      yield
        val observed = ratio(config.session, 400_000, 20260315L)
        assertTrue(observed > 8.0 * 0.9, observed < 8.0 * 1.1)
    },
    // Without the count, resumes alone give the ~2x of the first runs.
    test("the same profile without a refresh count stays below the target") {
      for config <- load(LoadgenConfigSpec.hocon)
      yield
        val off = config.session.copy(refreshCount = config.session.refreshCount.copy(mobileMean = 0.0))
        assertTrue(ratio(off, 100_000, 7L) < 5.0)
    },
    // 2.4 is the figure the cluster campaigns carry; it holds for a profile that logs in fully on 20% of
    // arrivals (resume 0.8), not for the 3.3% of the shipped one, where it would overshoot to about 13.
    test("a mean of 2.4 reaches the target range on a profile with 20% full logins") {
      for config <- load(LoadgenConfigSpec.hocon)
      yield
        val campaign = config.session.copy(
          fullLoginProbability = config.session.fullLoginProbability.copy(mobile = 0.2),
          refreshCount = config.session.refreshCount.copy(mobileMean = 2.4),
        )
        val observed = ratio(campaign, 400_000, 11L)
        assertTrue(observed > 5.0, observed < 10.0)
    },
  )

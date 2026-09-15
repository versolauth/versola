package versola.loadgen.coordinator

import zio.*
import zio.test.*

/** The published rates against the design doc's own derived figures.
  *
  * These numbers are the campaign: if the session rate is wrong, every latency in the report was
  * measured at a load nobody planned, and nothing in the report says so. So they are pinned
  * against §2.1's stated consequences of the mix -- DAU/registered = 34.6%, 2.7 sessions per DAU
  * -- rather than against whatever this code happens to compute.
  */
object ScenarioRatesSpec extends ZIOSpecDefault:

  private def close(actual: Double, expected: Double, tolerance: Double): Boolean =
    math.abs(actual - expected) <= tolerance

  def spec = suite("ScenarioRates")(
    test("reproduces the design doc's DAU share and sessions per DAU from the population mix") {
      for config <- CoordinatorFixture.coordinatorConfig
      yield
        val population = config.population
        val dau = population.classes.map(cohort => cohort.share * cohort.activeProbability).sum
        val sessionsPerDay = ScenarioRates.sessionsPerDay(population)
        val perDau = sessionsPerDay / (population.target.toDouble * dau)
        assertTrue(
          close(dau, 0.346, 0.001),
          close(perDau, 2.69, 0.01),
          close(sessionsPerDay, 9_310_000.0, 1.0),
        )
    },
    test("splits the session rate by platform and publishes no registration stream when disabled") {
      for
        config <- CoordinatorFixture.coordinatorConfig
        rates <- ZIO.fromEither(ScenarioRates.from(config.population, config.campaign, 8))
      yield
        val mobile = rates.basePerSecond(PlanScenario.MobileSession)
        val web = rates.basePerSecond(PlanScenario.WebSession)
        assertTrue(
          // 9.31M sessions/day is 107.75/s, of which 88% are mobile (§2.1).
          close(rates.sessionsPerSecond, 107.75, 0.01),
          close(mobile, 107.75 * 0.88, 0.01),
          close(web, 107.75 * 0.12, 0.01),
          // Absent, not zero: an absent scenario is one the driver must not run at all, while a
          // zero rate is one whose λ may come back on the next poll.
          !rates.basePerSecond.contains(PlanScenario.Registration),
        )
    },
    test("publishes the design doc's flat registration rate when the ramp is enabled") {
      for
        config <- CoordinatorFixture.registrationConfig
        rates <- ZIO.fromEither(ScenarioRates.from(config.population, config.campaign, 8))
      yield assertTrue(
        // 1,000,000 / 259,200 s = 3.86 reg/s (§2.4).
        close(rates.basePerSecond(PlanScenario.Registration), 3.858, 0.001),
      )
    },
    test("refuses a population mix the seeder would also have refused") {
      for
        config <- CoordinatorFixture.coordinatorConfig
        skewed = config.population.copy(platform = config.population.platform.copy(mobile = 0.5))
      yield assertTrue(ScenarioRates.from(skewed, config.campaign, 8).isLeft)
    },
    test("refuses a registration ramp with no target or no duration") {
      for
        config <- CoordinatorFixture.registrationConfig
        noTarget = config.campaign.copy(registration = config.campaign.registration.copy(target = 0L))
        noDuration = config.campaign.copy(registration = config.campaign.registration.copy(duration = Duration.Zero))
      yield assertTrue(
        ScenarioRates.from(config.population, noTarget, 8).isLeft,
        ScenarioRates.from(config.population, noDuration, 8).isLeft,
      )
    },
  )

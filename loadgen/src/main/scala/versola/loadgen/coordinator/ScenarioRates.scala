package versola.loadgen.coordinator

import versola.loadgen.config.{CampaignConfig, PopulationConfig}
import versola.loadgen.seed.PopulationPlan

/** The fleet-wide base rate of every arrival stream the plan publishes, derived from the
  * behaviour model rather than configured.
  *
  * Design doc §2.1 fixes the population mix and how often each cohort shows up; the session rate
  * is the arithmetic consequence, so it is computed here instead of being a knob. A knob would be
  * a second statement of the same thing, free to disagree with the mix the seeder wrote the
  * population from -- and a campaign generating a session rate that does not match its own
  * population is a campaign whose per-user cache and index locality claims are void.
  *
  * The knob that does exist is `campaign.phases[].scale`, which multiplies these (dev spec §5:
  * "the single knob every campaign uses").
  */
final case class ScenarioRates(basePerSecond: Map[PlanScenario, Double]):
  def sessionsPerSecond: Double =
    basePerSecond.getOrElse(PlanScenario.MobileSession, 0.0) +
      basePerSecond.getOrElse(PlanScenario.WebSession, 0.0)

object ScenarioRates:

  private val SecondsPerDay = 86_400.0

  /** Sessions per day for the whole population: `Σ target × share × P(active) × sessions`.
    *
    * At §5's mix and a 10M target this is 9.31M sessions/day -- the design doc's derived "DAU /
    * registered = 34.6%, 2.7 sessions per DAU per day", which is what `ScenarioRatesSpec` pins
    * it against. Both figures are consequences of this sum, not inputs, which is why neither is
    * in the config file.
    */
  def sessionsPerDay(population: PopulationConfig): Double =
    population.classes.foldLeft(0.0): (total, cohort) =>
      total + population.target.toDouble * cohort.share * cohort.activeProbability * cohort.sessions

  /** `campaign.registration`'s flat rate: the target divided by the ramp's length, before the
    * controller's factor and before the diurnal envelope. 1M over 72 h is the design doc §2.4's
    * 3.86 reg/s.
    */
  def registrationPerSecond(campaign: CampaignConfig): Double =
    campaign.registration.target.toDouble / campaign.registration.duration.toNanos.toDouble * 1e9

  /** @param shardCount used only to run the population mix through the seeder's own validator, so
    *                   a coordinator refuses to publish a plan derived from a mix the seeder
    *                   would have refused to write. Its one shard-count rule cannot fire here --
    *                   `PlanConfig.validate` has already rejected a non-positive count -- so the
    *                   `seed.shard-count` wording in that message is unreachable from this call.
    */
  def from(
      population: PopulationConfig,
      campaign: CampaignConfig,
      shardCount: Int,
  ): Either[String, ScenarioRates] =
    for
      _ <- PopulationPlan.validate(population, shardCount, population.target)
      _ <- registrationIsSchedulable(campaign)
    yield
      val sessionsPerSecond = sessionsPerDay(population) / SecondsPerDay
      val sessions = Map(
        PlanScenario.MobileSession -> sessionsPerSecond * population.platform.mobile,
        PlanScenario.WebSession -> sessionsPerSecond * population.platform.web,
      )
      // A disabled registration ramp publishes no registration rate at all rather than a rate of
      // zero. The two are different instructions to a driver: an absent scenario is one it must
      // not run, while a zero rate is one whose λ has momentarily fallen to nothing and which the
      // next poll may revive.
      ScenarioRates(
        if campaign.registration.enabled then sessions + (PlanScenario.Registration -> registrationPerSecond(campaign))
        else sessions,
      )

  private def registrationIsSchedulable(campaign: CampaignConfig): Either[String, Unit] =
    if !campaign.registration.enabled then Right(())
    else if campaign.registration.target <= 0L then
      Left(s"campaign.registration.target must be positive when the ramp is enabled, got ${campaign.registration.target}")
    else if campaign.registration.duration.toNanos <= 0L then
      Left(s"campaign.registration.duration must be positive when the ramp is enabled, got ${campaign.registration.duration}")
    else Right(())

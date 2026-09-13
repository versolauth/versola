package versola.loadgen.scheduler

import versola.loadgen.config.CampaignConfig
import versola.loadgen.config.CampaignPhaseConfig
import zio.Duration

import java.time.Instant

/** How a phase scales the base arrival rate (versola-loadgen-dev-spec.md §5's `campaign.phases`).
  *
  * Which of the two a phase is, is decided by **which fields are present**, not by the phase's
  * name: `scale` alone is flat, `scale-from` + `scale-to` is a linear ramp. §5 leaves this open
  * ("the scheduler decides which apply per phase `name`"), and name-based dispatch was rejected
  * -- `name` is free text used as a metric label, so a campaign that renamed `ramp` to
  * `ramp-up` would silently run flat.
  */
enum PhaseScale:
  case Flat(scale: Double)
  case Ramp(from: Double, to: Double)

/** One phase, with its absolute offset from the campaign start resolved. */
final case class CampaignPhase(name: String, startsAt: Duration, duration: Duration, scale: PhaseScale):
  def endsAt: Duration = startsAt.plus(duration)

  def scaleAt(elapsedInPhase: Duration): Double =
    scale match
      case PhaseScale.Flat(value) => value
      case PhaseScale.Ramp(from, to) =>
        // Linear in wall-clock time across the phase. Guarded against a zero-length phase, which
        // the config permits and which would otherwise be a division by zero at the instant the
        // phase is entered.
        val total = duration.toNanos
        if total <= 0L then to
        else
          val progress = math.min(math.max(elapsedInPhase.toNanos.toDouble / total, 0.0), 1.0)
          from + (to - from) * progress

/** The campaign's rate envelope over time (§7.3):
  *
  * {{{
  * rate(t) = base × scale(phase) × diurnal(hourOfDay)
  * }}}
  *
  * Phases run back to back from `startedAt` in the order the config lists them. Outside the
  * campaign -- before `startedAt` or after the last phase ends -- the scale is 0: a campaign that
  * has finished must generate nothing, and returning the last phase's scale instead is how a run
  * overruns its plan.
  */
final class CampaignSchedule private (val phases: Vector[CampaignPhase], val diurnal: DiurnalEnvelope, startedAt: Instant):
  val totalDuration: Duration = phases.foldLeft(Duration.Zero)((acc, phase) => acc.plus(phase.duration))

  def phaseAt(now: Instant): Option[CampaignPhase] =
    val elapsed = Duration.fromInterval(startedAt, now)
    if now.isBefore(startedAt) then None
    else phases.find(phase => elapsed.compareTo(phase.startsAt) >= 0 && elapsed.compareTo(phase.endsAt) < 0)

  def scaleAt(now: Instant): Double =
    phaseAt(now) match
      case Some(phase) => phase.scaleAt(Duration.fromInterval(startedAt, now).minus(phase.startsAt))
      case None => 0.0

  /** @return arrivals per second for the whole fleet; 0.0 outside the campaign, in which case the
    *         caller must stop scheduling rather than pass it to [[Exponential]] (which rejects a
    *         non-positive rate instead of generating an infinite gap).
    */
  def rateAt(baseRatePerSecond: Double, now: Instant): Double =
    val scale = scaleAt(now)
    if scale <= 0.0 then 0.0 else baseRatePerSecond * scale * diurnal.at(now)

  /** The instant the last phase ends. From here on `scaleAt` is 0, which is what bounds the
    * thinning loop in [[ArrivalProcess.next]]: without it, a campaign whose rate has fallen to
    * zero would be searched for an arrival that can never be accepted.
    */
  val endsAt: Instant = startedAt.plus(totalDuration)

  /** An upper bound on [[rateAt]] across the whole campaign: the largest scale any phase reaches,
    * times the diurnal peak.
    *
    * Thinning is only correct if the ceiling is one λ(t) provably never exceeds; a ceiling that is
    * merely usually right does not degrade gracefully, it drops the arrivals that would have been
    * accepted above it. Both factors are therefore maxima of closed forms rather than samples: a
    * ramp's extreme is at one of its endpoints, and the diurnal's is at its peak hour.
    */
  def rateCeiling(baseRatePerSecond: Double): Double =
    val peakScale = phases.foldLeft(0.0): (highest, phase) =>
      val phasePeak = phase.scale match
        case PhaseScale.Flat(value) => value
        case PhaseScale.Ramp(from, to) => math.max(from, to)
      math.max(highest, phasePeak)
    baseRatePerSecond * peakScale * diurnal.peak

  /** [[rateAt]] packaged for [[ArrivalProcess.next]]: the rate function, the ceiling it respects
    * and the horizon past which there is nothing left to schedule.
    */
  def envelope(baseRatePerSecond: Double): VaryingRate =
    VaryingRate(rateCeiling(baseRatePerSecond), endsAt, rateAt(baseRatePerSecond, _))

object CampaignSchedule:
  def from(config: CampaignConfig, startedAt: Instant): Either[String, CampaignSchedule] =
    for
      diurnal <- DiurnalEnvelope.from(config.diurnal)
      phases <- resolvePhases(config.phases)
    yield CampaignSchedule(phases, diurnal, startedAt)

  private def resolvePhases(configured: List[CampaignPhaseConfig]): Either[String, Vector[CampaignPhase]] =
    if configured.isEmpty then Left("campaign.phases must not be empty")
    else
      configured
        .foldLeft[Either[String, (Vector[CampaignPhase], Duration)]](Right((Vector.empty, Duration.Zero))):
          case (Left(error), _) => Left(error)
          case (Right((acc, offset)), phase) =>
            for
              _ <- CampaignPhaseConfig.validate(phase)
              scale <- scaleOf(phase)
            yield (acc :+ CampaignPhase(phase.name, offset, phase.duration, scale), offset.plus(phase.duration))
        .map((phases, _) => phases)

  /** The phase's shape, once [[CampaignPhaseConfig.validate]] has ruled out the combinations that
    * have none and the durations and scales that cannot be represented.
    *
    * Decoding runs the same validation, so for a campaign that came from HOCON this cannot fail.
    * It is re-run here because a `CampaignConfig` can also be built in code, and the point of
    * rejecting these configs is that nothing downstream should have to assume someone else did.
    */
  private def scaleOf(phase: CampaignPhaseConfig): Either[String, PhaseScale] =
    (phase.scale, phase.scaleFrom, phase.scaleTo) match
      case (Some(scale), None, None) => Right(PhaseScale.Flat(scale))
      case (None, Some(from), Some(to)) => Right(PhaseScale.Ramp(from, to))
      case _ => Left(s"campaign phase '${phase.name}' must set either scale, or both scale-from and scale-to")

package versola.loadgen.coordinator

import versola.loadgen.config.RegistrationConfig
import versola.loadgen.scheduler.DiurnalEnvelope
import zio.Duration

import java.time.Instant

/** The registration ramp's planned curve: how many users *should* exist by a given point into the
  * ramp (design doc §2.4, dev spec §12).
  *
  * Not `target × elapsed / duration`. The ramp's λ carries the same diurnal envelope as the rest
  * of the campaign, so a linear curve is behind the real plan every evening and ahead of it every
  * night -- and the controller, seeing that, would spend the campaign cancelling the diurnal
  * shape it was asked to produce. The honest curve is the integral of the envelope, normalised
  * over the ramp, which is what this is: `planned(t) = target × ∫₀ᵗ d(s) ds / ∫₀ᵀ d(s) ds`.
  *
  * Over a whole number of days the two agree -- the envelope's 24-hour mean is exactly 1.0 by
  * construction -- so this only differs from linear *within* a day, which is precisely where the
  * controller acts.
  */
final class RegistrationCurve private (target: Long, cumulative: Array[Double], stepNanos: Long):

  /** Planned population `elapsed` into the ramp, clamped to the ramp's own ends: before it
    * starts nothing is planned, and after it ends the plan is the full target rather than an
    * extrapolation past it.
    */
  def plannedAt(elapsed: Duration): Double =
    val total = cumulative(cumulative.length - 1)
    if total <= 0.0 then 0.0
    else
      val nanos = math.min(math.max(elapsed.toNanos.toDouble, 0.0), stepNanos.toDouble * (cumulative.length - 1))
      val position = nanos / stepNanos.toDouble
      val lower = math.min(position.toInt, cumulative.length - 1)
      val upper = math.min(lower + 1, cumulative.length - 1)
      val fraction = position - lower
      val integral = cumulative(lower) + (cumulative(upper) - cumulative(lower)) * fraction
      target.toDouble * integral / total

object RegistrationCurve:

  /** One panel per minute of the ramp, bounded at both ends: the integrand is smooth and its
    * daily period is 1,440 minutes, so a minute-wide trapezoid is orders of magnitude finer than
    * the controller's own 60-second period, and the bounds keep a misconfigured ramp (seconds
    * long, or years) from either degenerating or allocating without limit.
    */
  private val MinPanels = 64
  private val MaxPanels = 8_192
  private val PanelTargetNanos = 60L * 1_000_000_000L

  def from(diurnal: DiurnalEnvelope, startedAt: Instant, registration: RegistrationConfig): RegistrationCurve =
    val durationNanos = math.max(registration.duration.toNanos, 1L)
    val panels = math.min(math.max(durationNanos / PanelTargetNanos, MinPanels.toLong), MaxPanels.toLong).toInt
    // At least one nanosecond: a ramp shorter than its own panel count would otherwise step by
    // zero, and `plannedAt` divides by the step.
    val stepNanos = math.max(durationNanos / panels, 1L)
    val cumulative = new Array[Double](panels + 1)
    var index = 1
    var previous = diurnal.at(startedAt)
    while index <= panels do
      val at = diurnal.at(startedAt.plusNanos(stepNanos * index))
      cumulative(index) = cumulative(index - 1) + (previous + at) / 2.0
      previous = at
      index += 1
    RegistrationCurve(registration.target, cumulative, stepNanos)

/** The closed-loop registration controller of dev spec §12 and design doc §2.4.
  *
  * {{{
  * λ_registration = λ_nominal × clamp(1 + (planned − actual) / planned × k, 0.5, 2.0),  k = 3
  * }}}
  *
  * This is what lands a 72-hour ramp on exactly 1M users despite error attrition: registrations
  * that fail leave the actual count below the curve, the factor rises, and the ramp makes the
  * shortfall up rather than finishing short by however many users the SUT happened to reject.
  *
  * The period, the gain and the clamp are the spec's figures and are not configurable, for the
  * same reason `DiurnalEnvelope.SigmaHours` is not: they are the controller's tuning, and a
  * campaign that changed them would land somewhere else while reporting the same plan. The clamp
  * is what keeps the loop stable at k = 3 -- with a 60-second period against a 72-hour ramp the
  * loop is far slower than its own measurement, and the bounds mean even a completely wrong
  * reading can only halve or double the rate for one minute.
  */
object RegistrationController:

  val interval: Duration = Duration.fromSeconds(60)

  val gain: Double = 3.0

  val minFactor: Double = 0.5

  val maxFactor: Double = 2.0

  /** @param planned the curve's value now; `actual` the registered count in the emulator's own
    *                store, never the SUT's (design doc §2.4).
    */
  def factor(planned: Double, actual: Long): Double =
    // Nothing planned yet is the first tick of the ramp, when the error term is 0/0. The nominal
    // rate is the honest answer there: it is the rate the curve was derived from, so running at
    // it is what makes the first measurement meaningful.
    if planned <= 0.0 || planned.isNaN then 1.0
    else
      val error = (planned - actual.toDouble) / planned
      math.min(math.max(1.0 + error * gain, minFactor), maxFactor)

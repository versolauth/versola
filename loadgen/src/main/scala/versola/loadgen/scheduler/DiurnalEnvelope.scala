package versola.loadgen.scheduler

import versola.loadgen.config.DiurnalConfig

import java.time.Instant
import java.time.ZoneId
import scala.util.control.NonFatal

/** The time-of-day shape of the arrival rate (versola-loadgen-dev-spec.md §7.3):
  *
  * {{{
  * diurnal(h) = 1 + (peakFactor − 1) × exp(−wrap(h − peakHour)² / (2 × 3.5²))
  * }}}
  *
  * normalised so that its mean over 24 hours is exactly 1.0. The normalisation is not cosmetic:
  * without it, `base × scale × diurnal` runs the campaign at
  * `mean(diurnal) ≈ 1.73×` (at the configured `peak-factor = 3`) the volume the plan states, so
  * the capacity numbers the report is written against would be wrong by 36% while every
  * dashboard still looked shaped correctly. §7.3 says "otherwise every campaign silently runs at
  * a different total volume than the plan states" -- and worse, a *different* wrong volume for
  * each `peak-factor` (the inflation is linear in it), so two campaigns would not be comparable.
  *
  * Hours are wall-clock hours in the campaign's configured timezone, so a peak that is meant to
  * be 20:00 in Almaty stays at 20:00 across a DST transition in some other zone.
  */
final class DiurnalEnvelope private (
    peakFactor: Double,
    peakHour: Double,
    zone: ZoneId,
    dailyMean: Double,
):
  /** @param hourOfDay hours since local midnight, fractional; values outside `[0, 24)` wrap. */
  def atHour(hourOfDay: Double): Double = DiurnalEnvelope.unnormalised(hourOfDay, peakFactor, peakHour) / dailyMean

  def at(instant: Instant): Double = atHour(DiurnalEnvelope.localHourOf(instant, zone))

  /** The factor the 24-hour mean was divided by. Exposed for the report: it is the ratio between
    * the raw §7.3 formula and what the campaign actually runs at.
    */
  def normalisationFactor: Double = dailyMean

  /** The largest value [[at]] can return, exactly rather than by search: §7.3's Gaussian term is
    * maximal where its wrapped distance is zero, which is the peak hour. Used as the diurnal
    * factor of `CampaignSchedule.rateCeiling`, where an under-estimate would silently lose
    * arrivals.
    */
  def peak: Double = atHour(peakHour)

object DiurnalEnvelope:
  /** Width of the peak in hours, from §7.3's `2 × 3.5²`. Not configurable there, so not
    * configurable here -- a second knob that changes total volume, with no plan to set it from,
    * is how campaigns stop being comparable.
    */
  val SigmaHours: Double = 3.5

  private val HoursPerDay = 24.0

  // Composite Simpson over one day for the normaliser. The closed form is an `erf`, which
  // java.lang.Math does not have and which is not worth hand-rolling for a constant computed
  // once per campaign. 2880 panels is a 30-second step: Simpson's O(h⁴) error puts this ~1e-11
  // from exact, four orders of magnitude below the 1e-6 the test asserts, so the residual is the
  // test's own quadrature error and not this. Even panel count is required by the rule; the
  // integrand's only kink (the antipode of the peak, where the wrap flips sign) lands on a panel
  // boundary for any integral `peak-hour`, so full order is retained there too.
  private val NormaliserPanels = 2880

  def from(config: DiurnalConfig): Either[String, DiurnalEnvelope] =
    if !config.enabled then
      // A flat envelope, not an absent one: the rest of the scheduler multiplies by this
      // unconditionally, and `Option` here would push a branch into every rate computation.
      Right(DiurnalEnvelope(peakFactor = 1.0, peakHour = 0.0, zone = ZoneId.of("UTC"), dailyMean = 1.0))
    else if config.peakFactor < 1.0 then
      // Below 1 the "peak" is a trough and the formula's `1 +` baseline becomes the maximum.
      // Legal arithmetic, certainly not what `peak-factor` was asked for.
      Left(s"campaign.diurnal.peak-factor must be >= 1.0, got ${config.peakFactor}")
    else if config.peakHour < 0 || config.peakHour > 23 then
      Left(s"campaign.diurnal.peak-hour must be in [0, 23], got ${config.peakHour}")
    else
      parseZone(config.timezone).map: zone =>
        val peakHour = config.peakHour.toDouble
        DiurnalEnvelope(config.peakFactor, peakHour, zone, dailyMean(config.peakFactor, peakHour))

  private def parseZone(timezone: String): Either[String, ZoneId] =
    try Right(ZoneId.of(timezone))
    catch case NonFatal(_) => Left(s"campaign.diurnal.timezone is not a known zone id: '$timezone'")

  /** §7.3's formula verbatim, before normalisation. */
  private def unnormalised(hourOfDay: Double, peakFactor: Double, peakHour: Double): Double =
    val distance = wrapToHalfDay(hourOfDay - peakHour)
    1.0 + (peakFactor - 1.0) * math.exp(-(distance * distance) / (2.0 * SigmaHours * SigmaHours))

  /** Signed hours to the peak, wrapped to `[−12, +12]` -- 02:00 is two hours from a 00:00 peak,
    * not twenty-two.
    */
  private def wrapToHalfDay(hours: Double): Double =
    val positive = ((hours % HoursPerDay) + HoursPerDay) % HoursPerDay
    if positive > HoursPerDay / 2.0 then positive - HoursPerDay else positive

  private def dailyMean(peakFactor: Double, peakHour: Double): Double =
    val step = HoursPerDay / NormaliserPanels
    var total = unnormalised(0.0, peakFactor, peakHour) + unnormalised(HoursPerDay, peakFactor, peakHour)
    var index = 1
    while index < NormaliserPanels do
      val weight = if index % 2 == 0 then 2.0 else 4.0
      total += weight * unnormalised(index * step, peakFactor, peakHour)
      index += 1
    (total * step / 3.0) / HoursPerDay

  private def localHourOf(instant: Instant, zone: ZoneId): Double =
    instant.atZone(zone).toLocalTime.toNanoOfDay / 3.6e12

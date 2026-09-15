package versola.loadgen.coordinator

import versola.loadgen.config.{DiurnalConfig, RegistrationConfig}
import versola.loadgen.scheduler.DiurnalEnvelope
import zio.*
import zio.test.*

import java.time.Instant

/** The closed loop of §12, and the curve it is closed against.
  *
  * The formula is stated in the spec, so the tests here are about the two things a formula cannot
  * state: that the clamp really bounds a bad reading, and that the planned curve follows the
  * diurnal envelope rather than a straight line -- which is what stops the controller from
  * fighting the envelope every night.
  */
object RegistrationControllerSpec extends ZIOSpecDefault:

  private val ramp = RegistrationConfig(enabled = true, target = 1_000_000L, duration = 72.hours)

  private val diurnal = DiurnalEnvelope
    .from(DiurnalConfig(enabled = true, peakFactor = 3.0, peakHour = 20, timezone = "Asia/Almaty"))
    .toOption
    .get

  private val flat = DiurnalEnvelope
    .from(DiurnalConfig(enabled = false, peakFactor = 1.0, peakHour = 0, timezone = "UTC"))
    .toOption
    .get

  private val startedAt = Instant.parse("2026-09-15T00:00:00Z")

  def spec = suite("RegistrationController")(
    suite("factor")(
      test("is 1.0 exactly on the curve, and the spec's k = 3 either side of it") {
        assertTrue(
          RegistrationController.factor(planned = 100_000.0, actual = 100_000L) == 1.0,
          // 10% behind × 3 = +30%.
          RegistrationController.factor(planned = 100_000.0, actual = 90_000L) == 1.3,
          // 10% ahead × 3 = −30%.
          RegistrationController.factor(planned = 100_000.0, actual = 110_000L) == 0.7,
        )
      },
      test("clamps to [0.5, 2.0], so one wrong reading cannot run away with the ramp") {
        assertTrue(
          RegistrationController.factor(planned = 100_000.0, actual = 0L) == RegistrationController.maxFactor,
          RegistrationController.factor(planned = 100_000.0, actual = 1_000_000L) == RegistrationController.minFactor,
          RegistrationController.maxFactor == 2.0,
          RegistrationController.minFactor == 0.5,
          RegistrationController.gain == 3.0,
          RegistrationController.interval == 60.seconds,
        )
      },
      test("runs at the nominal rate on the first tick, when the error term is 0/0") {
        assertTrue(
          RegistrationController.factor(planned = 0.0, actual = 0L) == 1.0,
          RegistrationController.factor(planned = Double.NaN, actual = 0L) == 1.0,
        )
      },
    ),
    suite("curve")(
      test("starts at nothing, ends on the target, and never goes backwards") {
        val curve = RegistrationCurve.from(diurnal, startedAt, ramp)
        val samples = (0 to 72).map(hour => curve.plannedAt(hour.hours))
        assertTrue(
          curve.plannedAt(Duration.Zero) == 0.0,
          math.abs(curve.plannedAt(72.hours) - 1_000_000.0) < 1.0,
          samples.sliding(2).forall(pair => pair(1) >= pair(0)),
        )
      },
      test("is clamped at both ends rather than extrapolated past the ramp") {
        val curve = RegistrationCurve.from(diurnal, startedAt, ramp)
        assertTrue(
          curve.plannedAt(-5.hours) == 0.0,
          curve.plannedAt(500.hours) == curve.plannedAt(72.hours),
        )
      },
      test("is the integral of the envelope, not a straight line through it") {
        val shaped = RegistrationCurve.from(diurnal, startedAt, ramp)
        val linear = RegistrationCurve.from(flat, startedAt, ramp)
        // The ramp opens at 05:00 Almaty, fifteen hours before the 20:00 peak, so its first
        // hours are its quietest: the shaped curve plans materially fewer users by hour four than
        // a straight line does. A linear plan would read that deficit as the ramp running behind
        // and wind λ up -- which is the controller cancelling the diurnal shape it was asked to
        // produce.
        assertTrue(
          shaped.plannedAt(4.hours) < linear.plannedAt(4.hours) * 0.7,
          math.abs(linear.plannedAt(36.hours) - 500_000.0) < 1_000.0,
          // Over a whole number of days the two agree: the envelope's 24-hour mean is 1.0.
          math.abs(shaped.plannedAt(24.hours) - linear.plannedAt(24.hours)) < 5_000.0,
        )
      },
      test("a zero-length or zero-target ramp plans nothing rather than dividing by it") {
        val empty = RegistrationCurve.from(diurnal, startedAt, ramp.copy(target = 0L))
        val instant = RegistrationCurve.from(diurnal, startedAt, ramp.copy(duration = Duration.Zero))
        assertTrue(
          empty.plannedAt(36.hours) == 0.0,
          instant.plannedAt(36.hours) == 1_000_000.0,
        )
      },
    ),
  )

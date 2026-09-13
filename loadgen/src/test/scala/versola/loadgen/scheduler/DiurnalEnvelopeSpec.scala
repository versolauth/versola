package versola.loadgen.scheduler

import versola.loadgen.config.DiurnalConfig
import zio.test.*

import java.time.ZoneId
import java.time.ZonedDateTime

/** §13's "diurnal envelope integrates to 1.0". Deterministic by construction -- the envelope
  * draws no randomness -- so no seed is involved here.
  */
object DiurnalEnvelopeSpec extends ZIOSpecDefault:

  private val zone = "Asia/Almaty"

  private def config(enabled: Boolean, peakFactor: Double, peakHour: Int): DiurnalConfig =
    DiurnalConfig(enabled = enabled, peakFactor = peakFactor, peakHour = peakHour, timezone = zone)

  /** Midpoint rule over one-second steps.
    *
    * Deliberately a *different* quadrature rule, and a different node set, from the composite
    * Simpson that `DiurnalEnvelope` uses to compute its own normaliser: integrating with the same
    * rule would return 1.0 by cancellation whatever the formula did, and assert nothing.
    *
    * Its own error is what sets the tolerance below. Midpoint error is `(b−a)h²·max|f''|/24`;
    * with `h = 1/3600 h` and `max|f''| = (peakFactor−1)/σ² ≈ 0.4` at `peakFactor = 6`, that is
    * ~3e-9. 1e-6 therefore leaves two and a half orders of magnitude of slack over the test's own
    * numerics while still catching any real error in the normalisation -- for reference, dropping
    * the normalisation entirely moves this number to 1.73 at `peakFactor = 3`.
    */
  private def dailyMean(envelope: DiurnalEnvelope): Double =
    val steps = 86400
    val step = 24.0 / steps
    var total = 0.0
    var index = 0
    while index < steps do
      total += envelope.atHour((index + 0.5) * step)
      index += 1
    total / steps

  private val tolerance = 1e-6

  def spec = suite("DiurnalEnvelope")(
    suite("normalisation")(
      test("integrates to 1.0 over 24 hours, at every peak factor and peak hour") {
        val cases =
          for
            peakFactor <- List(1.0, 1.5, 3.0, 6.0)
            peakHour <- List(0, 3, 8, 12, 20, 23)
          yield (peakFactor, peakHour)
        val means = cases.map: (peakFactor, peakHour) =>
          val envelope = DiurnalEnvelope.from(config(enabled = true, peakFactor, peakHour)).toOption.get
          (peakFactor, peakHour, dailyMean(envelope))
        assertTrue(means.forall((_, _, mean) => math.abs(mean - 1.0) < tolerance))
      },
      test("the raw §7.3 formula does not integrate to 1.0 -- so the normaliser is load-bearing") {
        val envelope = DiurnalEnvelope.from(config(enabled = true, peakFactor = 3.0, peakHour = 20)).toOption.get
        // 1 + (peakFactor − 1) × σ√(2π)·erf(12/σ√2)/24 for σ = 3.5: the 73% volume inflation the
        // normalisation removes.
        assertTrue(math.abs(envelope.normalisationFactor - 1.7307) < 1e-3)
      },
      test("a disabled envelope is flat at 1.0") {
        val envelope = DiurnalEnvelope.from(config(enabled = false, peakFactor = 3.0, peakHour = 20)).toOption.get
        assertTrue(
          (0 until 24).forall(hour => envelope.atHour(hour.toDouble) == 1.0),
          math.abs(dailyMean(envelope) - 1.0) < tolerance,
        )
      },
    ),
    suite("shape")(
      test("peaks at peak-hour and troughs at its antipode") {
        val envelope = DiurnalEnvelope.from(config(enabled = true, peakFactor = 3.0, peakHour = 20)).toOption.get
        val hourly = (0 until 24).map(hour => envelope.atHour(hour.toDouble))
        assertTrue(
          hourly.zipWithIndex.maxBy(_._1)._2 == 20,
          hourly.zipWithIndex.minBy(_._1)._2 == 8,
          math.abs(envelope.atHour(20.0) - 3.0 / envelope.normalisationFactor) < 1e-12,
        )
      },
      test("wraps across midnight rather than treating 23:00 as far from a 00:00 peak") {
        val envelope = DiurnalEnvelope.from(config(enabled = true, peakFactor = 3.0, peakHour = 0)).toOption.get
        assertTrue(math.abs(envelope.atHour(23.0) - envelope.atHour(1.0)) < 1e-12)
      },
      test("reads the hour in the configured timezone, not UTC") {
        val envelope = DiurnalEnvelope.from(config(enabled = true, peakFactor = 3.0, peakHour = 20)).toOption.get
        val localPeak = ZonedDateTime.of(2026, 9, 10, 20, 0, 0, 0, ZoneId.of(zone)).toInstant
        assertTrue(math.abs(envelope.at(localPeak) - envelope.atHour(20.0)) < 1e-12)
      },
    ),
    suite("validation")(
      test("rejects an unknown timezone") {
        assertTrue(DiurnalEnvelope.from(config(enabled = true, 3.0, 20).copy(timezone = "Mars/Olympus")).isLeft)
      },
      test("rejects a peak factor below 1.0, which would make the peak a trough") {
        assertTrue(DiurnalEnvelope.from(config(enabled = true, peakFactor = 0.5, peakHour = 20)).isLeft)
      },
      test("rejects a peak hour outside the day") {
        assertTrue(DiurnalEnvelope.from(config(enabled = true, peakFactor = 3.0, peakHour = 24)).isLeft)
      },
    ),
  )

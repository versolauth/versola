package versola.loadgen.scheduler

import versola.loadgen.config.ActionCountConfig
import versola.loadgen.config.ThinkTimeConfig
import versola.loadgen.model.Platform
import zio.durationInt
import zio.test.*

/** §13's "NegBinomial/LogNormal moments match their parameters".
  *
  * Every draw comes from [[RandomSource.seeded]] with a literal seed, so these are exact
  * regression assertions rather than samples that happen to pass: a failure means the sampler
  * changed, not that the run was unlucky. The tolerances are nevertheless stated in units of the
  * estimator's standard error, so that they say what deviation the test is actually sensitive to.
  *
  * Sample size is 100,000 draws per distribution -- ~0.3% standard error on the means here, and
  * a few tens of milliseconds on one core.
  */
object DistributionsSpec extends ZIOSpecDefault:

  private val samples = 100_000

  private val thinkTime = ThinkTimeConfig(median = 4.seconds, sigma = 0.8)
  private val actionCount = ActionCountConfig(mobileMean = 6.0, webMean = 10.0, dispersion = 0.6)

  private def moments(draws: IndexedSeq[Double]): (Double, Double) =
    val mean = draws.sum / draws.size
    val variance = draws.map(draw => (draw - mean) * (draw - mean)).sum / (draws.size - 1)
    (mean, variance)

  private def relative(actual: Double, expected: Double): Double = math.abs(actual - expected) / expected

  def spec = suite("Distributions")(
    suite("LogNormal")(
      test("sample mean and variance match median+sigma") {
        val random = RandomSource.seeded(20260910L)
        val draws = IndexedSeq.fill(samples)(LogNormal.sample(median = 4.0, sigma = 0.8, random))
        val (mean, variance) = moments(draws)
        // Mean: sd/√n is 0.30% of the mean, so 1.5% is ~5 standard errors.
        // Variance: a LogNormal at sigma = 0.8 has excess kurtosis ~31, giving s² a relative
        // standard error of ~1.8%; 10% is ~5.5 of those. Both are wide enough never to flake and
        // narrow enough to catch the failure mode that matters -- reading `median` as the
        // underlying normal's mu, which moves the mean by 14x.
        assertTrue(
          relative(mean, LogNormal.mean(4.0, 0.8)) < 0.015,
          relative(variance, LogNormal.variance(4.0, 0.8)) < 0.10,
        )
      },
      test("the median is the configured median, and is not the mean") {
        val random = RandomSource.seeded(20260911L)
        val draws = IndexedSeq.fill(samples)(LogNormal.sample(median = 4.0, sigma = 0.8, random)).sorted
        assertTrue(
          relative(draws(samples / 2), 4.0) < 0.02,
          relative(LogNormal.mean(4.0, 0.8), 5.5088) < 1e-4,
        )
      },
      test("rejects a non-positive median or sigma") {
        val random = RandomSource.seeded(1L)
        assertTrue(
          scala.util.Try(LogNormal.sample(0.0, 0.8, random)).isFailure,
          scala.util.Try(LogNormal.sample(4.0, 0.0, random)).isFailure,
        )
      },
    ),
    suite("NegBinomial")(
      test("sample mean and variance match mean+dispersion, at both configured means") {
        val random = RandomSource.seeded(20260912L)
        val mobile = IndexedSeq.fill(samples)(ActionCount.sample(actionCount, Platform.Mobile, random).toDouble)
        val web = IndexedSeq.fill(samples)(ActionCount.sample(actionCount, Platform.Web, random).toDouble)
        val (mobileMean, mobileVariance) = moments(mobile)
        val (webMean, webVariance) = moments(web)
        assertTrue(
          relative(mobileMean, 6.0) < 0.015,
          relative(webMean, 10.0) < 0.015,
          // Var = mean × (1 + dispersion × mean): 27.6 mobile, 70.0 web. Same tolerance
          // reasoning as LogNormal above; the alternative parameterisation (dispersion read as
          // the size r) would put these at 66.0 and 176.7, i.e. 2.4x out.
          relative(mobileVariance, 27.6) < 0.10,
          relative(webVariance, 70.0) < 0.10,
          relative(ActionCount.varianceFor(actionCount, Platform.Mobile), 27.6) < 1e-12,
        )
      },
      test("draws zero for the documented share of sessions") {
        val random = RandomSource.seeded(20260913L)
        val draws = IndexedSeq.fill(samples)(ActionCount.sample(actionCount, Platform.Mobile, random))
        // P(N = 0) = (r/(r+mean))^r with r = 1/0.6: 7.86%. Asserted rather than clamped away,
        // because clamping to a minimum of one action would move the realised mean off the
        // configured 6.0 without saying so.
        val zeroShare = draws.count(_ == 0).toDouble / samples
        assertTrue(math.abs(zeroShare - 0.0786) < 0.005, draws.forall(_ >= 0))
      },
      test("a dispersion above 1 (size below 1) still samples -- the Gamma boost branch") {
        val random = RandomSource.seeded(20260914L)
        val draws = IndexedSeq.fill(samples)(NegBinomial.sample(mean = 6.0, dispersion = 2.5, random).toDouble)
        val (mean, variance) = moments(draws)
        assertTrue(
          relative(mean, 6.0) < 0.03,
          relative(variance, NegBinomial.variance(6.0, 2.5)) < 0.15,
        )
      },
    ),
    suite("ThinkTimeTable")(
      test("the precomputed table reproduces the configured median and honours the clamp") {
        val table = ThinkTimeTable.build(thinkTime, RandomSource.seeded(20260915L))
        val entries = table.entriesMillis.sorted
        val random = RandomSource.seeded(20260916L)
        val drawn = IndexedSeq.fill(samples)(table.sample(random).toMillis.toDouble)
        val (mean, _) = moments(drawn)
        assertTrue(
          entries.length == ThinkTimeTable.Size,
          entries.head >= ThinkTimeTable.FloorMillis,
          entries.last <= ThinkTimeTable.CeilingMillis,
          relative(entries(ThinkTimeTable.Size / 2).toDouble, 4000.0) < 0.02,
          // The clamp is 3.7 sigma below and 4.3 sigma above the median, so it moves the mean by
          // far less than the tolerance -- which is what makes the table usable as a LogNormal.
          relative(mean, ThinkTimeTable.unclampedMeanMillis(thinkTime)) < 0.03,
        )
      },
      test("a 200 ms median is lifted to the floor rather than sampled below it") {
        val table = ThinkTimeTable.build(ThinkTimeConfig(median = 200.millis, sigma = 0.8), RandomSource.seeded(20260917L))
        val entries = table.entriesMillis
        assertTrue(
          entries.forall(_ >= ThinkTimeTable.FloorMillis),
          entries.count(_ == ThinkTimeTable.FloorMillis.toInt) > ThinkTimeTable.Size / 4,
        )
      },
    ),
    suite("ActionCount")(
      test("maps platform to the configured mean") {
        assertTrue(
          ActionCount.meanFor(actionCount, Platform.Mobile) == 6.0,
          ActionCount.meanFor(actionCount, Platform.Web) == 10.0,
        )
      },
    ),
  )

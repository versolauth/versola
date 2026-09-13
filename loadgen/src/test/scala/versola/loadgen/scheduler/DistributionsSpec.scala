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
  // Means of the user-driven actions only: a session is these plus the app's mandatory opening
  // `GET /accounts`, so the totals are still design doc §2.3's 6.0 mobile and 10.0 web.
  private val actionCount = ActionCountConfig(mobileMean = 5.0, webMean = 9.0, dispersion = 0.6)

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
          // The session totals of §2.3, reached as 1 + NegBinomial(5.0) and 1 + NegBinomial(9.0).
          relative(mobileMean, 6.0) < 0.015,
          relative(webMean, 10.0) < 0.015,
          // Var = mean × (1 + dispersion × mean) of the *draw*: 20.0 mobile, 57.6 web. The
          // mandatory action shifts the distribution and so leaves the variance alone. Same
          // tolerance reasoning as LogNormal above; the alternative parameterisation (dispersion
          // read as the size r) would put these at 45.0 and 138.6, i.e. 2.3x out.
          relative(mobileVariance, 20.0) < 0.10,
          relative(webVariance, 57.6) < 0.10,
          relative(ActionCount.varianceFor(actionCount, Platform.Mobile), 20.0) < 1e-12,
        )
      },
      test("every session performs at least the app's opening call, without the draw being clamped") {
        val random = RandomSource.seeded(20260913L)
        val draws = IndexedSeq.fill(samples)(ActionCount.sample(actionCount, Platform.Mobile, random))
        // P(N' = 0) = (r/(r+mean))^r with r = 1/0.6 and mean 5: 9.87% -- a user who opens the app,
        // sees their balance and closes it. Those sessions still perform one action, because §3's
        // `GET /accounts` is the app's call and was never part of the draw. Asserting the share
        // rather than clamping keeps the low tail unbiased and the realised mean exactly 6.0.
        val singleActionShare = draws.count(_ == 1).toDouble / samples
        assertTrue(math.abs(singleActionShare - 0.0987) < 0.005, draws.forall(_ >= ActionCount.mandatoryActions))
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
      test("maps platform to the configured mean, and adds the mandatory action to it") {
        assertTrue(
          ActionCount.additionalMeanFor(actionCount, Platform.Mobile) == 5.0,
          ActionCount.additionalMeanFor(actionCount, Platform.Web) == 9.0,
          ActionCount.sessionMeanFor(actionCount, Platform.Mobile) == 6.0,
          ActionCount.sessionMeanFor(actionCount, Platform.Web) == 10.0,
        )
      },
    ),
  )

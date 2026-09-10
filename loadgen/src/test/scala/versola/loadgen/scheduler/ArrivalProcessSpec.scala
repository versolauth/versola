package versola.loadgen.scheduler

import zio.test.*

import java.time.Instant

/** §13's "arrival inter-times are exponential (χ² test)", plus the properties that make the
  * schedule a *schedule* rather than a sequence of clock reads (§7.2).
  *
  * Sample size is 50,000 inter-arrival times at λ = 25/s, from a literal seed. 50,000 gives
  * 5,000 expected observations per bin -- two orders of magnitude above the 5-per-bin rule the
  * χ² approximation needs -- and generates in a few milliseconds on one core.
  */
object ArrivalProcessSpec extends ZIOSpecDefault:

  private val anchor = Instant.parse("2026-09-10T00:00:00Z")
  private val rate = 25.0
  private val samples = 50_000
  private val bins = 10

  /** χ²(0.99) with 9 degrees of freedom = 21.666.
    *
    * Bin count 10, so df = bins − 1 = 9: no parameter is estimated from the sample (λ is the one
    * the process was driven with), so no further degree of freedom is lost. Significance level
    * 0.01 rather than 0.05 (critical value 16.919) because this assertion runs on every CI build:
    * the seed is fixed so the statistic is deterministic, but a future re-seeding should not have
    * a 1-in-20 chance of failing a correct sampler. Hard-coded rather than computed, so that the
    * suite does not acquire a statistics dependency for one number. The statistic this seed
    * actually produces is 14.79, i.e. it also clears the stricter 0.05 threshold.
    */
  private val chiSquaredCritical = 21.666

  private def interArrivalSeconds(seed: Long, count: Int, ratePerSecond: Double): IndexedSeq[Double] =
    val process = ArrivalProcess.startingAt(anchor, RandomSource.seeded(seed))
    val arrivals = process.take(count + 1, ratePerSecond)
    (0 until count).map: index =>
      val from = arrivals(index).intendedStart
      val to = arrivals(index + 1).intendedStart
      (to.getEpochSecond - from.getEpochSecond).toDouble + (to.getNano - from.getNano) / 1e9

  /** Equiprobable bins: boundaries at the deciles of Exponential(λ), so every expected count is
    * `n/bins` and the statistic does not depend on an arbitrary bin width.
    */
  private def chiSquared(draws: IndexedSeq[Double], ratePerSecond: Double): Double =
    val boundaries = (1 until bins).map(index => Exponential.quantile(index.toDouble / bins, ratePerSecond))
    val counts = Array.ofDim[Int](bins)
    draws.foreach: draw =>
      val bin = boundaries.indexWhere(boundary => draw < boundary)
      counts(if bin < 0 then bins - 1 else bin) += 1
    val expected = draws.size.toDouble / bins
    counts.map(count => (count - expected) * (count - expected) / expected).sum

  def spec = suite("ArrivalProcess")(
    suite("inter-arrival distribution")(
      test("inter-arrival times pass a chi-squared goodness-of-fit test against Exponential(λ)") {
        val statistic = chiSquared(interArrivalSeconds(20260910L, samples, rate), rate)
        assertTrue(statistic < chiSquaredCritical)
      },
      test("the same test rejects a wrong rate -- so it is a test and not a tautology") {
        // Half the rate: the same draws against Exponential(12.5) put ~all the mass in the low
        // bins. Guards against the boundary/binning arithmetic above degenerating into something
        // that passes for any input.
        val statistic = chiSquared(interArrivalSeconds(20260910L, samples, rate), rate / 2)
        assertTrue(statistic > chiSquaredCritical)
      },
      test("mean inter-arrival time is 1/λ") {
        val draws = interArrivalSeconds(20260911L, samples, rate)
        val mean = draws.sum / draws.size
        // sd of an exponential equals its mean, so the standard error here is 1/(λ√n) = 0.45% of
        // 1/λ; 2% is ~4.5 of those.
        assertTrue(math.abs(mean - 1.0 / rate) / (1.0 / rate) < 0.02)
      },
    ),
    suite("schedule")(
      test("intended starts are monotone and sequence numbers are dense from 1") {
        val process = ArrivalProcess.startingAt(anchor, RandomSource.seeded(20260912L))
        val arrivals = process.take(1_000, rate)
        assertTrue(
          arrivals.map(_.sequence) == zio.Chunk.fromIterable(1L to 1000L),
          arrivals.head.intendedStart.isAfter(anchor),
          arrivals.zip(arrivals.drop(1)).forall((left, right) => right.intendedStart.isAfter(left.intendedStart)),
          arrivals.last.intendedStart == process.lastScheduledAt,
        )
      },
      test("the schedule is a function of anchor and seed only -- it never reads the clock") {
        // Two processes built at different real times from the same anchor and seed must produce
        // the identical schedule. This is the property that makes schedule lag observable: if
        // generation depended on when it ran, `now − intendedStart` would be zero by
        // construction and a driver falling behind would be invisible.
        val first = ArrivalProcess.startingAt(anchor, RandomSource.seeded(7L)).take(500, rate)
        val second = ArrivalProcess.startingAt(anchor, RandomSource.seeded(7L)).take(500, rate)
        val other = ArrivalProcess.startingAt(anchor, RandomSource.seeded(8L)).take(500, rate)
        assertTrue(first == second, first != other)
      },
      test("a rate change applies from the next draw, so λ(t) can move under the generator") {
        val process = ArrivalProcess.startingAt(anchor, RandomSource.seeded(20260913L))
        val slow = (0 until 2_000).map(_ => process.next(1.0))
        val slowSpan = slow.last.intendedStart.getEpochSecond - anchor.getEpochSecond
        val fastStart = process.lastScheduledAt
        val fast = (0 until 2_000).map(_ => process.next(100.0))
        val fastSpan = fast.last.intendedStart.getEpochSecond - fastStart.getEpochSecond
        assertTrue(slowSpan > 1_500L, fastSpan < 30L)
      },
      test("a driver's share of the published rate is λ/shardCount") {
        assertTrue(
          ArrivalProcess.shardRate(2_650.0, 8) == 331.25,
          scala.util.Try(ArrivalProcess.shardRate(2_650.0, 0)).isFailure,
        )
      },
      test("rejects a non-positive rate rather than scheduling an arrival at infinity") {
        val process = ArrivalProcess.startingAt(anchor, RandomSource.seeded(20260914L))
        assertTrue(scala.util.Try(process.next(0.0)).isFailure)
      },
    ),
  )

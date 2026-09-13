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
    suite("varying rate")(
      test("a gap that crosses a rate change is distributed under the rate that applies across it") {
        // λ is 0.01/s for the first 10 s and 1000/s for the next 10 s. A generator that fixes the
        // rate at the start of the gap draws its first gap with a mean of 100 s, overshoots the
        // whole 20 s window and produces nothing at all; the rate change inside the gap is never
        // seen. Thinning evaluates λ at the candidate instant, so the fast half yields its own
        // ~10,000 arrivals (Poisson, sd 100 -- the bounds below are 4σ).
        val step = anchor.plusSeconds(10)
        val rate = VaryingRate(
          ceiling = 1_000.0,
          endsAt = anchor.plusSeconds(20),
          at = instant => if instant.isBefore(step) then 0.01 else 1_000.0,
        )
        val arrivals = ArrivalProcess.startingAt(anchor, RandomSource.seeded(20260915L)).take(20_000, rate)
        val afterStep = arrivals.count(!_.intendedStart.isBefore(step))
        val beforeStep = arrivals.size - afterStep
        assertTrue(beforeStep <= 3, afterStep > 9_600, afterStep < 10_400)
      },
      test("the arrival count over an interval matches the integral of λ across it") {
        // λ ramps linearly 0 -> 200/s over 60 s, so the expected count is the area, 6,000
        // (Poisson sd 77.5; the bounds are ~4σ). A gap drawn at the rate in force when it started
        // would systematically under-count on a rising ramp, since every gap would be priced at a
        // rate lower than the one that actually applies by the time it lands.
        val span = 60.0
        val peak = 200.0
        val rate = VaryingRate(
          ceiling = peak,
          endsAt = anchor.plusSeconds(span.toLong),
          at = instant =>
            val elapsed = (instant.toEpochMilli - anchor.toEpochMilli) / 1000.0
            peak * (elapsed / span),
        )
        val arrivals = ArrivalProcess.startingAt(anchor, RandomSource.seeded(20260916L)).take(50_000, rate)
        // Half the area lies in the last 17.6 s of the ramp (1 - 1/√2 of its length), which a
        // flat-rate generator could not reproduce even with the right total.
        val lastQuarter = arrivals.count(_.intendedStart.isAfter(anchor.plusSeconds(30)))
        assertTrue(
          arrivals.size > 5_690,
          arrivals.size < 6_310,
          lastQuarter.toDouble / arrivals.size > 0.70,
          lastQuarter.toDouble / arrivals.size < 0.80,
        )
      },
      test("stops at the horizon, leaving the process resumable from it") {
        val endsAt = anchor.plusSeconds(5)
        val rate = VaryingRate(ceiling = 10.0, endsAt = endsAt, at = _ => 10.0)
        val process = ArrivalProcess.startingAt(anchor, RandomSource.seeded(20260917L))
        val arrivals = process.take(10_000, rate)
        assertTrue(
          arrivals.forall(_.intendedStart.isBefore(endsAt)),
          arrivals.size < 10_000,
          // No arrival fell in the tail of the window just searched, so the memoryless resume
          // point is the horizon itself, not the last accepted arrival.
          process.lastScheduledAt == endsAt,
          process.next(rate).isEmpty,
        )
      },
      test("a zero rate inside the horizon yields nothing rather than looping") {
        val rate = VaryingRate(ceiling = 10.0, endsAt = anchor.plusSeconds(5), at = _ => 0.0)
        val process = ArrivalProcess.startingAt(anchor, RandomSource.seeded(20260918L))
        assertTrue(process.next(rate).isEmpty, process.take(10, rate).isEmpty)
      },
      test("rejects an envelope whose rate exceeds its declared ceiling") {
        // Silently thinning against a too-low ceiling loses exactly the arrivals above it, and the
        // deficit is indistinguishable from the SUT absorbing less load.
        val rate = VaryingRate(ceiling = 1.0, endsAt = anchor.plusSeconds(60), at = _ => 5.0)
        val process = ArrivalProcess.startingAt(anchor, RandomSource.seeded(20260919L))
        assertTrue(scala.util.Try(process.take(100, rate)).isFailure)
      },
      test("the varying-rate schedule is a function of anchor and seed only") {
        val rate = VaryingRate(
          ceiling = 50.0,
          endsAt = anchor.plusSeconds(30),
          at = instant => if instant.isBefore(anchor.plusSeconds(15)) then 5.0 else 50.0,
        )
        val first = ArrivalProcess.startingAt(anchor, RandomSource.seeded(11L)).take(5_000, rate)
        val second = ArrivalProcess.startingAt(anchor, RandomSource.seeded(11L)).take(5_000, rate)
        val other = ArrivalProcess.startingAt(anchor, RandomSource.seeded(12L)).take(5_000, rate)
        assertTrue(first == second, first != other, first.map(_.sequence) == zio.Chunk.fromIterable(1L to first.size.toLong))
      },
    ),
  )

package versola.mockapi

import zio.Duration

import java.util.concurrent.ThreadLocalRandom

/** Which mixture an endpoint draws from. Reads and writes differ only in the branch weights
  * (design doc §3: writes shift mass from the cache branch to the store and core-banking
  * branches), never in the branch shapes.
  */
enum DelayProfile:
  case Read
  case Write

/** Weights of the three branches of the backend delay mixture (design doc §3, "Backend delay
  * distribution"): a cache hit, one DB read, and a core-banking call. They must sum to 1.
  */
final case class MixtureWeights(cacheHit: Double, dbRead: Double, coreBanking: Double)

object MixtureWeights:
  val read: MixtureWeights = MixtureWeights(0.70, 0.28, 0.02)
  val write: MixtureWeights = MixtureWeights(0.40, 0.50, 0.10)

/** What the startup self-check holds the sampler to. Milliseconds, because that is the unit the
  * design doc states them in and the unit the failure message has to be read in.
  */
final case class QuantileTargets(p50Millis: Double, p95Millis: Double, p99Millis: Double)

final case class AchievedQuantiles(
    p50Millis: Double,
    p90Millis: Double,
    p95Millis: Double,
    p99Millis: Double,
    meanMillis: Double,
)

/** Samples the backend delay from a precomputed table.
  *
  * The table is 65,536 microsecond delays and a request does exactly one bounds-checked array
  * read per lookup: no `Math.log`, no `Math.exp`, no allocation. That matters because this
  * process is the reference the whole campaign's latency numbers are measured against -- any
  * per-request maths here shows up as a systematic addition to every p99 the report quotes.
  *
  * The table is built by inverse-CDF stratification rather than by drawing 65,536 random
  * samples: each branch gets its share of the slots filled at evenly spaced probabilities, so
  * the table's own quantiles are the mixture's quantiles to within the spacing, with no sampling
  * error to argue about and nothing seed-dependent for a test to flake on. Draw order is
  * irrelevant since lookup is uniform over the whole table.
  */
final class DelaySampler private (micros: Array[Int], durations: Array[Duration]):

  private lazy val sortedMicros: Array[Int] = micros.sorted

  /** One `ThreadLocalRandom` draw. Callers keep the index so that the delay they sleep and the
    * delay they report to the histogram cannot drift apart.
    */
  def drawIndex(): Int = ThreadLocalRandom.current().nextInt(micros.length)

  def microsAt(index: Int): Int = micros(index)

  def durationAt(index: Int): Duration = durations(index)

  def size: Int = micros.length

  /** Quantile of the table itself, not of a sample drawn from it. */
  def tableQuantileMicros(quantile: Double): Int =
    val rank = math.round(quantile * (sortedMicros.length - 1)).toInt
    sortedMicros(math.max(0, math.min(sortedMicros.length - 1, rank)))

  def tableMeanMicros: Double =
    var total = 0L
    var i = 0
    while i < micros.length do
      total += micros(i)
      i += 1
    total.toDouble / micros.length

object DelaySampler:

  /** A power of two so the `nextInt` bound is a mask, and large enough that the 2%/10%
    * core-banking branch still gets four figures' worth of distinct values (dev spec §9).
    */
  val TableSize: Int = 65536

  private val FloorMillis: Double = 1.0
  private val ClampMillis: Double = 50.0

  private val CacheMedianMillis: Double = 4.0
  private val CacheSigma: Double = 0.50
  private val DbMedianMillis: Double = 18.0
  private val DbSigma: Double = 0.45
  private val CoreLowMillis: Double = 40.0
  private val CoreHighMillis: Double = 50.0

  /** From the design doc §3 composite (p50 ≈ 6 ms, p95 ≈ 30 ms, p99 ≈ 46 ms). */
  val readTargets: QuantileTargets = QuantileTargets(6.0, 30.0, 46.0)

  /** The doc publishes no composite for the 40/50/10 write mixture, so these are the analytic
    * quantiles of that mixture under the same floor and clamp. p99 sits exactly on the 50 ms
    * clamp because the write weights put 1.6% of the mass past it -- a property of the
    * configured weights, not of this implementation.
    */
  val writeTargets: QuantileTargets = QuantileTargets(13.5, 47.0, 50.0)

  def targetsFor(profile: DelayProfile): QuantileTargets =
    profile match
      case DelayProfile.Read => readTargets
      case DelayProfile.Write => writeTargets

  def weightsFor(profile: DelayProfile): MixtureWeights =
    profile match
      case DelayProfile.Read => MixtureWeights.read
      case DelayProfile.Write => MixtureWeights.write

  def make(weights: MixtureWeights): DelaySampler =
    val cacheSlots = math.round(weights.cacheHit * TableSize).toInt
    val dbSlots = math.round(weights.dbRead * TableSize).toInt
    val coreSlots = TableSize - cacheSlots - dbSlots

    val micros = new Array[Int](TableSize)
    var slot = 0

    def fill(count: Int, quantileMillis: Double => Double): Unit =
      var i = 0
      while i < count do
        // Midpoint of the i-th of `count` equal probability strata: never 0 or 1, so the
        // lognormal branches stay finite at the ends.
        val p = (i + 0.5) / count
        micros(slot) = toMicros(quantileMillis(p))
        slot += 1
        i += 1

    fill(cacheSlots, p => logNormalQuantile(CacheMedianMillis, CacheSigma, p))
    fill(dbSlots, p => logNormalQuantile(DbMedianMillis, DbSigma, p))
    fill(coreSlots, p => CoreLowMillis + (CoreHighMillis - CoreLowMillis) * p)

    val durations = Array.tabulate(TableSize)(i => Duration.fromNanos(micros(i).toLong * 1000L))
    new DelaySampler(micros, durations)

  /** Draws `count` times through the same path a request takes, so the self-check measures the
    * sampler as deployed rather than the table it was built from.
    */
  def sampleQuantiles(sampler: DelaySampler, count: Int): AchievedQuantiles =
    val drawn = new Array[Int](count)
    var total = 0L
    var i = 0
    while i < count do
      val value = sampler.microsAt(sampler.drawIndex())
      drawn(i) = value
      total += value
      i += 1
    java.util.Arrays.sort(drawn)

    def quantile(q: Double): Double =
      val rank = math.round(q * (count - 1)).toInt
      drawn(math.max(0, math.min(count - 1, rank))) / 1000.0

    AchievedQuantiles(
      p50Millis = quantile(0.50),
      p90Millis = quantile(0.90),
      p95Millis = quantile(0.95),
      p99Millis = quantile(0.99),
      meanMillis = total.toDouble / count / 1000.0,
    )

  /** Names the quantiles that are off by more than `tolerance` (a fraction, e.g. 0.10), in a
    * form that can go straight into a startup failure message.
    */
  def deviations(
      achieved: AchievedQuantiles,
      targets: QuantileTargets,
      tolerance: Double,
  ): List[String] =
    List(
      ("p50", achieved.p50Millis, targets.p50Millis),
      ("p95", achieved.p95Millis, targets.p95Millis),
      ("p99", achieved.p99Millis, targets.p99Millis),
    ).collect:
      case (name, got, target) if math.abs(got - target) > tolerance * target =>
        f"$name%s achieved ${got}%.2f ms, target ${target}%.2f ms (±${tolerance * 100}%.0f%%)"

  /** The 1 ms floor is a shift, not a `max`: the design doc §3 phrases it as "shifted by a 1 ms
    * floor", and only the shift reproduces the composite quantiles it publishes (a `max` leaves
    * p50 at 5.3 ms against a stated 6 ms, which the self-check's ±10% would then reject).
    */
  private def toMicros(branchMillis: Double): Int =
    val millis = math.min(FloorMillis + branchMillis, ClampMillis)
    math.max(1, math.round(millis * 1000.0).toInt)

  private def logNormalQuantile(medianMillis: Double, sigma: Double, p: Double): Double =
    medianMillis * math.exp(sigma * probit(p))

  private val probitA = Array(
    -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
    1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00,
  )
  private val probitB = Array(
    -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
    6.680131188771972e+01, -1.328068155288572e+01,
  )
  private val probitC = Array(
    -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
    -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00,
  )
  private val probitD = Array(
    7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00,
  )

  /** Acklam's rational approximation of the standard normal inverse CDF (relative error
    * ~1e-9). Used only while building the table, never per request.
    */
  private def probit(p: Double): Double =
    val low = 0.02425
    val high = 1.0 - low
    if p < low then
      val q = math.sqrt(-2.0 * math.log(p))
      (((((probitC(0) * q + probitC(1)) * q + probitC(2)) * q + probitC(3)) * q + probitC(4)) * q + probitC(5)) /
        ((((probitD(0) * q + probitD(1)) * q + probitD(2)) * q + probitD(3)) * q + 1.0)
    else if p > high then
      val q = math.sqrt(-2.0 * math.log(1.0 - p))
      -(((((probitC(0) * q + probitC(1)) * q + probitC(2)) * q + probitC(3)) * q + probitC(4)) * q + probitC(5)) /
        ((((probitD(0) * q + probitD(1)) * q + probitD(2)) * q + probitD(3)) * q + 1.0)
    else
      val q = p - 0.5
      val r = q * q
      (((((probitA(0) * r + probitA(1)) * r + probitA(2)) * r + probitA(3)) * r + probitA(4)) * r + probitA(5)) * q /
        (((((probitB(0) * r + probitB(1)) * r + probitB(2)) * r + probitB(3)) * r + probitB(4)) * r + 1.0)

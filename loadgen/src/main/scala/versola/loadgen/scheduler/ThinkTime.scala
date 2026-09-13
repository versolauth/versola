package versola.loadgen.scheduler

import versola.loadgen.config.ThinkTimeConfig
import zio.Duration

/** Think time between a session's actions: `LogNormal(median, σ)` "sampled from a precomputed
  * 64k table, clamped to `[200 ms, 120 s]`" (versola-loadgen-dev-spec.md §7.4).
  *
  * The table is the same trick `mockapi`'s `DelaySampler` uses (§9): one `log`/`exp` pair per
  * draw is cheap in isolation, but think time is drawn once per action, i.e. ~5,000 times a
  * second per driver, and the sampler is on the path that decides *when* load is generated. A
  * table turns it into one uniform, one multiply and an array read, and -- more usefully -- makes
  * the sampled distribution a fixed, inspectable artifact of the campaign seed rather than
  * something re-derived per draw.
  *
  * The clamp is why the table is built from millisecond integers: nothing finer than a
  * millisecond survives it, and `Array[Int]` keeps the 64k entries in 256 KiB.
  */
final class ThinkTimeTable private (millis: Array[Int]):
  /** One draw. The uniform indexes the table directly rather than being consumed by any
    * arithmetic, so the table's empirical distribution *is* the sampled distribution.
    */
  def sample(random: RandomSource): Duration =
    Duration.fromMillis(millis((random.nextDouble() * millis.length).toInt))

  /** The table itself, for the calibration self-check of §9/§13 (quantiles of what will actually
    * be sampled, not of the distribution it was drawn from).
    */
  def entriesMillis: Array[Int] = millis.clone()

object ThinkTimeTable:
  /** §7.4's "64k". A power of two so the index arithmetic is exact in `Double`. */
  val Size: Int = 65536

  val FloorMillis: Long = 200L
  val CeilingMillis: Long = 120_000L

  /** @param random seeded by the caller -- the campaign seed in production, a literal in tests.
    *               The table is drawn once at construction, so this is the only place the think
    *               time distribution consumes randomness.
    */
  def build(config: ThinkTimeConfig, random: RandomSource): ThinkTimeTable =
    val medianMillis = config.median.toMillis.toDouble
    require(medianMillis > 0.0, s"session.think-time.median must be positive, got ${config.median}")
    val entries = Array.ofDim[Int](Size)
    var index = 0
    while index < Size do
      val draw = LogNormal.sample(medianMillis, config.sigma, random)
      entries(index) = math.min(math.max(math.round(draw), FloorMillis), CeilingMillis).toInt
      index += 1
    ThinkTimeTable(entries)

  /** Mean of the *unclamped* LogNormal, in milliseconds. The clamp moves this by well under a
    * millisecond at the configured 4 s / 0.8 -- the floor is 3.7σ below the median and the
    * ceiling 4.3σ above it -- but the two are not the same number, so the name says which.
    */
  def unclampedMeanMillis(config: ThinkTimeConfig): Double =
    LogNormal.mean(config.median.toMillis.toDouble, config.sigma)

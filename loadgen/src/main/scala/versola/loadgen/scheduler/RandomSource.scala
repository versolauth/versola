package versola.loadgen.scheduler

import java.util.SplittableRandom

/** The single source of randomness every distribution in this package draws from.
  *
  * A seam rather than a bare `SplittableRandom` because of what
  * versola-loadgen-dev-spec.md §13 asks of this package: the diurnal, moment and χ² tests are
  * statistical, so they are only a regression net if the stream behind them is pinned. A test
  * constructs [[RandomSource.seeded]] with a literal seed and gets the same draws on every
  * machine and every run. Production takes the same path -- the campaign seed is split once per
  * driver fiber -- which is what rules out `ThreadLocalRandom` here even though §3.3 prefers it
  * on the protocol hot path: its stream cannot be pinned, so a run could not be replayed and a
  * failing campaign could not be reproduced.
  *
  * Deliberately narrower than `java.util.random.RandomGenerator`, and specifically it does not
  * inherit that interface's `nextGaussian`: the JDK's Gaussian algorithm is not part of its
  * compatibility contract, so a JDK upgrade would silently move every seeded expectation in the
  * suite. [[nextGaussian]] below is fixed here instead.
  */
trait RandomSource:
  /** Uniform on `[0, 1)`. */
  def nextDouble(): Double

  /** A generator for another fiber, advancing independently of this one. */
  def split(): RandomSource

  /** Uniform on `(0, 1]` -- the half-open end that `log`, and any `pow(u, negative)`, need. */
  final def nextDoubleOpenAtZero(): Double = 1.0 - nextDouble()

  /** Standard normal by the Marsaglia polar method.
    *
    * Rejection sampling rather than plain Box-Muller: no `sin`/`cos` per draw, and the rejected
    * region is only 1 − π/4 ≈ 21% of the square. The polar method produces a pair of independent
    * normals and this discards the second, which costs ~2 extra uniforms per call. That is the
    * price of keeping the trait free of per-instance cache state -- the callers that draw
    * normals in bulk ([[Gamma]] via [[NegBinomial]], [[LogNormal]]) are off the request path,
    * and think time is sampled from a precomputed table anyway (§7.4).
    */
  final def nextGaussian(): Double =
    var result = 0.0
    var accepted = false
    while !accepted do
      val x = 2.0 * nextDouble() - 1.0
      val y = 2.0 * nextDouble() - 1.0
      val s = x * x + y * y
      if s > 0.0 && s < 1.0 then
        result = x * math.sqrt(-2.0 * math.log(s) / s)
        accepted = true
    result

object RandomSource:
  /** The only constructor: a seed is always explicit, never derived from the clock or from
    * `ThreadLocalRandom.current()`, so that "which seed produced this run" is always answerable.
    */
  def seeded(seed: Long): RandomSource = SplittableRandomSource(SplittableRandom(seed))

private final class SplittableRandomSource(underlying: SplittableRandom) extends RandomSource:
  def nextDouble(): Double = underlying.nextDouble()

  def split(): RandomSource = SplittableRandomSource(underlying.split())

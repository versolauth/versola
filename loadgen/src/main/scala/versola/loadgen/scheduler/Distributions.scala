package versola.loadgen.scheduler

/** Exponential inter-arrival times for the open arrival model of
  * versola-loadgen-dev-spec.md §7.2, parameterised by **rate** (λ, arrivals per second), not by
  * mean -- the coordinator publishes λ and the driver's share is λ/shardCount, so rate is the
  * quantity that actually travels between components.
  *
  * Inverse-CDF, `−ln(U)/λ`: exact, one uniform and one `log` per draw, and monotone in the
  * uniform -- which is what makes the χ² test in `ArrivalProcessSpec` a test of the arrival
  * process rather than of a rejection loop.
  */
object Exponential:
  /** @return seconds until the next arrival; `Infinity` never occurs because the uniform is
    *         drawn on `(0, 1]`.
    */
  def sample(ratePerSecond: Double, random: RandomSource): Double =
    require(ratePerSecond > 0.0, s"exponential rate must be positive, got $ratePerSecond")
    -math.log(random.nextDoubleOpenAtZero()) / ratePerSecond

  /** Quantile function, used by the goodness-of-fit test to build equiprobable bins. */
  def quantile(probability: Double, ratePerSecond: Double): Double =
    -math.log(1.0 - probability) / ratePerSecond

/** LogNormal think time (§7.4), parameterised by **median and σ** -- which is what
  * `session.think-time { median = 4s, sigma = 0.8 }` in §5 gives us, and the reason this
  * distinction is spelled out: the other common convention is (μ, σ) of the underlying normal,
  * and the two agree only because `median = exp(μ)`. Reading `median = 4s` as μ = 4 would
  * produce a median of `e⁴ ≈ 55 s` -- a 14× error that no test of the scenario engine would
  * notice, because the shape would still look like a plausible think time.
  *
  * Consequences of the parameterisation worth having in front of the reader: the **mean is not
  * the median** (`median × exp(σ²/2)`, so 5.51 s for the configured 4 s / 0.8), and the mean is
  * what determines session wall-clock duration and therefore the concurrent-user count.
  */
object LogNormal:
  def sample(median: Double, sigma: Double, random: RandomSource): Double =
    require(median > 0.0, s"lognormal median must be positive, got $median")
    require(sigma > 0.0, s"lognormal sigma must be positive, got $sigma")
    median * math.exp(sigma * random.nextGaussian())

  def mean(median: Double, sigma: Double): Double = median * math.exp(sigma * sigma / 2.0)

  def variance(median: Double, sigma: Double): Double =
    val m = mean(median, sigma)
    m * m * (math.exp(sigma * sigma) - 1.0)

/** Actions per session (§7.4, design doc §2.3), parameterised by **mean and dispersion** to
  * match `session.action-count { mobile-mean = 6, web-mean = 10, dispersion = 0.6 }`.
  *
  * NegBinomial has two live conventions and the config names neither, so this fixes it: `mean`
  * is μ, and `dispersion` is the **NB2 overdispersion coefficient α**, giving
  *
  * {{{
  * Var = μ (1 + α μ)        size r = 1/α        Gamma-Poisson mixture Poisson(Gamma(r, μ/r))
  * }}}
  *
  * The alternative reading -- `dispersion` as the size/shape `r` itself -- was rejected because
  * it does not survive the config having *one* dispersion for *two* means: at r = 0.6 the mobile
  * variance would be `6 + 6²/0.6 = 66` (sd 8.1 on a mean of 6, so most sessions are 0-2 actions
  * and the tail carries the volume), whereas α = 0.6 gives `6 × (1 + 3.6) = 27.6` (sd 5.3).
  * α is the dimensionless one, and so the only one that means the same thing at both means.
  *
  * Note that a NegBinomial is supported at 0: with these parameters `P(N = 0) = (r/(r+μ))^r`
  * ≈ 7.9% mobile, 5.0% web -- a session where the user opens the app and does nothing. That is
  * left in rather than clamped, because clamping would shift the realised mean off the
  * configured one (to ≈ 6.08) silently. Design doc §3 calls action #1 "the first call of every
  * session", which contradicts it; see the report on #273.
  */
object NegBinomial:
  def sample(mean: Double, dispersion: Double, random: RandomSource): Int =
    require(mean > 0.0, s"negbinomial mean must be positive, got $mean")
    require(dispersion > 0.0, s"negbinomial dispersion must be positive, got $dispersion")
    val size = 1.0 / dispersion
    Poisson.sample(Gamma.sample(size, mean / size, random), random)

  def variance(mean: Double, dispersion: Double): Double = mean * (1.0 + dispersion * mean)

/** Gamma by shape and **scale** (not rate) -- `E[X] = shape × scale`. Only ever reached through
  * [[NegBinomial]]'s mixture, hence package-private.
  */
private[scheduler] object Gamma:
  def sample(shape: Double, scale: Double, random: RandomSource): Double =
    if shape < 1.0 then
      // Marsaglia-Tsang's own boost for shape < 1, where the squeeze below is invalid:
      // Gamma(a) == Gamma(a + 1) × U^(1/a). Reachable from config -- dispersion > 1 means
      // size < 1 -- so it is not dead code.
      sample(shape + 1.0, scale, random) * math.pow(random.nextDoubleOpenAtZero(), 1.0 / shape)
    else
      // Marsaglia-Tsang (2000): one normal and one uniform per attempt, acceptance > 95% at
      // every shape >= 1, and no rejection loop over the whole density.
      val d = shape - 1.0 / 3.0
      val c = 1.0 / math.sqrt(9.0 * d)
      var result = 0.0
      var accepted = false
      while !accepted do
        val x = random.nextGaussian()
        val v = 1.0 + c * x
        if v > 0.0 then
          val v3 = v * v * v
          val u = random.nextDoubleOpenAtZero()
          if math.log(u) < 0.5 * x * x + d - d * v3 + d * math.log(v3) then
            result = d * v3 * scale
            accepted = true
      result

/** Poisson by Knuth's product method. `O(λ)` uniforms per draw, which is the right trade at the
  * λ this package sees (the Gamma mixing mean is 6-10, §5): the alternatives that are `O(1)` in
  * λ cost a far larger constant and a table.
  */
private[scheduler] object Poisson:
  // exp(-λ) underflows to 0 around λ = 745, at which point Knuth's loop never terminates.
  // Poisson is additive in λ, so a large λ is drawn as a sum of chunks; this bound leaves three
  // orders of magnitude of headroom under the underflow.
  private val MaxChunk = 500.0

  def sample(lambda: Double, random: RandomSource): Int =
    require(lambda >= 0.0, s"poisson lambda must be non-negative, got $lambda")
    var remaining = lambda
    var total = 0L
    while remaining > 0.0 do
      val chunk = math.min(remaining, MaxChunk)
      total += chunkSample(chunk, random)
      remaining -= chunk
    total.toInt

  private def chunkSample(lambda: Double, random: RandomSource): Int =
    val threshold = math.exp(-lambda)
    var product = random.nextDouble()
    var count = 0
    while product > threshold do
      count += 1
      product *= random.nextDouble()
    count

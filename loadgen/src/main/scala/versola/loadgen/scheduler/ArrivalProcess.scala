package versola.loadgen.scheduler

import zio.Chunk

import java.time.Instant

/** One scheduled unit of work and the instant it was *meant* to start.
  *
  * `intendedStart` comes out of the arrival recurrence, never from a clock read
  * (versola-loadgen-dev-spec.md §7.2: "records `intendedStart = scheduledAt`, not
  * `Instant.now()`"). Everything downstream -- the step histograms, the flow histograms and
  * `loadgen_schedule_lag_seconds` -- is measured against it, which is the coordinated-omission
  * correction the whole measurement rests on.
  */
final case class ScheduledArrival(sequence: Long, intendedStart: Instant)

/** A rate that changes with time, packaged with the two facts thinning needs to be correct and to
  * terminate.
  *
  * @param ceiling an upper bound `at` never exceeds anywhere in `[now, endsAt)`. Thinning accepts
  *                a candidate with probability `at(t) / ceiling`, so a ceiling that is too low
  *                does not merely lose precision -- it discards the arrivals that would have been
  *                accepted above it. `CampaignSchedule.rateCeiling` derives one in closed form.
  * @param endsAt  the instant past which the rate is zero for good, which is what stops the search
  *                for a candidate that can never be accepted.
  * @param at      λ(t) in arrivals per second.
  */
final case class VaryingRate(ceiling: Double, endsAt: Instant, at: Instant => Double)

/** The open-model arrival process of §7.2: one scenario's stream of intended start times on one
  * shard.
  *
  * {{{
  * scheduledAt = previousScheduledAt + Exponential(λ_shard)
  * }}}
  *
  * The recurrence is anchored on the campaign's start instant and advances only by drawn
  * inter-arrival times, so the schedule is a pure function of `(anchor, seed, λ history)`. That
  * is the property that makes falling behind *observable*: the intended schedule exists
  * independently of whether the driver managed to execute it, so `now − intendedStart` is a real
  * quantity. Read the clock anywhere in here -- e.g. by anchoring each arrival on the moment the
  * previous one finished -- and the process silently degrades into a closed loop that generates
  * less load at the same apparent rate, with a schedule lag that is structurally zero.
  *
  * Not thread-safe and not meant to be: §7.2 has one generator per scenario per driver filling a
  * bounded queue ahead of the clock, and one owner is also what keeps the draws reproducible.
  */
final class ArrivalProcess private (random: RandomSource, anchor: Instant):
  private var previous: Instant = anchor
  private var sequence: Long = 0L

  /** Next arrival at a rate that is **constant over the whole gap this draws**.
    *
    * The entire inter-arrival time is distributed at `ratePerSecond`, so this is only the right
    * call when λ genuinely does not change before the arrival lands. §7.2's λ(t) does change --
    * phase transitions, the ramp inside a phase, the diurnal envelope, a plan re-published every
    * 10 s -- and a gap drawn at the rate in force when it was drawn keeps the old rate all the way
    * across any of those, which is exactly what makes the first arrivals after every transition,
    * and therefore the campaign's load shape and total volume, wrong. Use [[next(rate:VaryingRate)]]
    * for that; this overload stays for the genuinely homogeneous case (a flat phase with the
    * diurnal disabled) and for the goodness-of-fit tests, where a pure inverse-CDF draw is the
    * thing under test.
    */
  def next(ratePerSecond: Double): ScheduledArrival =
    previous = advance(previous, Exponential.sample(ratePerSecond, random))
    sequence += 1
    ScheduledArrival(sequence, previous)

  /** Next arrival under a rate that varies continuously, by Lewis-Shedler thinning.
    *
    * Candidates are drawn at `rate.ceiling` and each is kept with probability
    * `rate.at(candidate) / rate.ceiling`. Because the acceptance test is evaluated at the
    * candidate's own instant rather than at the previous arrival's, a gap that crosses a ramp, a
    * phase boundary, a diurnal change or a re-published rate is distributed under the rate that
    * actually applies across it -- which the plain exponential draw above cannot do, whatever rate
    * it is handed.
    *
    * @return `None` once the horizon is reached with nothing accepted. `lastScheduledAt` is left
    *         at `rate.endsAt` in that case: no arrival occurred in the interval just searched, and
    *         the exponential is memoryless, so a later call under an extended horizon resumes from
    *         there correctly instead of re-drawing an interval already decided.
    */
  def next(rate: VaryingRate): Option[ScheduledArrival] =
    require(rate.ceiling > 0.0, s"rate ceiling must be positive, got ${rate.ceiling}")
    var scheduled: Option[ScheduledArrival] = None
    var candidate = previous
    var exhausted = false
    while scheduled.isEmpty && !exhausted do
      candidate = advance(candidate, Exponential.sample(rate.ceiling, random))
      if !candidate.isBefore(rate.endsAt) then
        previous = rate.endsAt
        exhausted = true
      else
        val intensity = rate.at(candidate)
        // A rate above the declared ceiling is a broken envelope, not a rare draw: every arrival
        // the excess should have produced is already gone by the time anyone inspects the output,
        // and the deficit looks exactly like the SUT absorbing less load. NaN fails this too.
        require(
          intensity <= rate.ceiling,
          s"rate $intensity at $candidate exceeds the declared ceiling ${rate.ceiling}",
        )
        if intensity > 0.0 && random.nextDouble() * rate.ceiling < intensity then
          previous = candidate
          sequence += 1
          scheduled = Some(ScheduledArrival(sequence, candidate))
    scheduled

  def take(count: Int, ratePerSecond: Double): Chunk[ScheduledArrival] =
    Chunk.fill(count)(next(ratePerSecond))

  /** As [[take]], but under a varying rate: stops early at the horizon, so the result can be
    * shorter than `count`.
    */
  def take(count: Int, rate: VaryingRate): Chunk[ScheduledArrival] =
    val arrivals = Chunk.newBuilder[ScheduledArrival]
    var remaining = count
    var running = true
    while running && remaining > 0 do
      next(rate) match
        case Some(arrival) =>
          arrivals += arrival
          remaining -= 1
        case None => running = false
    arrivals.result()

  // Split whole seconds from the fraction rather than rounding the whole gap to nanos: at the low
  // rates a night trough or a 0.1 scale can produce (design doc §2.4 has 1.2 reg/s at night, and
  // `scale = 0.1` on top of that), a single gap can exceed the ~9.2e9 s that fit in a nanosecond
  // Long.
  private def advance(from: Instant, gapSeconds: Double): Instant =
    val wholeSeconds = math.floor(gapSeconds).toLong
    val fractionNanos = math.round((gapSeconds - wholeSeconds) * 1e9)
    from.plusSeconds(wholeSeconds).plusNanos(fractionNanos)

  /** Intended start of the most recently generated arrival, or the anchor if none has been. How
    * far ahead of the clock the generator has run.
    */
  def lastScheduledAt: Instant = previous

object ArrivalProcess:
  /** @param anchor the campaign (or phase) start instant that the recurrence hangs off. */
  def startingAt(anchor: Instant, random: RandomSource): ArrivalProcess = ArrivalProcess(random, anchor)

  /** A driver's share of a published rate: `λ(t) / shardCount` (§7.2).
    *
    * Splitting a Poisson process by a constant factor across N shards yields N independent
    * Poisson processes whose superposition is the original, so the fleet generates the published
    * λ without the drivers coordinating -- which is the same argument that removes the
    * distributed lock in §7.1.
    */
  def shardRate(ratePerSecond: Double, shardCount: Int): Double =
    require(shardCount > 0, s"shard count must be positive, got $shardCount")
    ratePerSecond / shardCount

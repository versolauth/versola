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

  /** Next arrival at the rate current for this instant of the plan. λ is a parameter of the call
    * rather than of the process because §7.2's λ(t) changes under the driver -- phase
    * transitions, the diurnal envelope, a re-published plan every 10 s -- and an inter-arrival
    * time drawn at the rate in force when it is drawn is the standard way to run a
    * piecewise-constant Poisson process.
    */
  def next(ratePerSecond: Double): ScheduledArrival =
    val gapSeconds = Exponential.sample(ratePerSecond, random)
    // Split whole seconds from the fraction rather than rounding the whole gap to nanos: at the
    // low rates a night trough or a 0.1 scale can produce (design doc §2.4 has 1.2 reg/s at
    // night, and `scale = 0.1` on top of that), a single gap can exceed the ~9.2e9 s that fit in
    // a nanosecond Long.
    val wholeSeconds = math.floor(gapSeconds).toLong
    val fractionNanos = math.round((gapSeconds - wholeSeconds) * 1e9)
    previous = previous.plusSeconds(wholeSeconds).plusNanos(fractionNanos)
    sequence += 1
    ScheduledArrival(sequence, previous)

  def take(count: Int, ratePerSecond: Double): Chunk[ScheduledArrival] =
    Chunk.fill(count)(next(ratePerSecond))

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

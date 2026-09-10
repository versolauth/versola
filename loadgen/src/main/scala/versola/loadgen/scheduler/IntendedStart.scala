package versola.loadgen.scheduler

import zio.Clock
import zio.Duration
import zio.Ref
import zio.UIO

import java.time.Instant

/** Latency measurement anchored on the *intended* start of a scheduled unit
  * (versola-loadgen-dev-spec.md §7.2, design doc §6.3).
  *
  * The rule is one line -- "all latency is measured from `intendedStart`" -- and it is the
  * difference between an instrument and a thing that produces numbers. If a fiber times itself
  * from the moment it actually got scheduled, then when the driver falls behind, every
  * measurement stays small and the report says the SUT is fine while a third of the intended load
  * was never generated. Measuring from the intended start folds the driver's own queueing delay
  * into the same histogram, so the failure appears as latency rather than as absence.
  *
  * The two quantities are kept separate on purpose:
  *   - [[latency]] is what goes into `loadgen_step_duration_seconds` / the HdrHistograms (§11).
  *   - [[startDelay]] is the *driver's own* contribution to that latency, i.e. the part that is
  *     the emulator's fault and not the SUT's. Without it, a run that breached
  *     `schedule_lag < 250 ms` (§15) cannot be told apart from a genuinely slow SUT after the
  *     fact.
  */
object IntendedStart:
  /** End-to-end latency of a scheduled unit, from when it should have started. */
  def latency(intendedStart: Instant, completedAt: Instant): Duration =
    Duration.fromInterval(intendedStart, completedAt)

  /** How late the driver was in starting a unit it had already scheduled.
    *
    * Floored at zero: a unit is never started before its intended start (the worker sleeps until
    * then), so a negative value can only come from a clock adjustment, and a negative "lag" in a
    * gauge reads as a healthy driver rather than as a broken clock.
    */
  def startDelay(intendedStart: Instant, actualStart: Instant): Duration =
    nonNegativeBetween(intendedStart, actualStart)

  private[scheduler] def nonNegativeBetween(from: Instant, to: Instant): Duration =
    if to.isBefore(from) then Duration.Zero else Duration.fromInterval(from, to)

/** The `loadgen.driver.schedule_lag` gauge of §7.2 / §11: `now − scheduledAt` of the **head of
  * the scheduled queue**, i.e. of the oldest arrival that has not started yet.
  *
  * Head-of-queue, not last-completed, because that is what makes the metric leading rather than
  * lagging: an arrival whose turn came 400 ms ago and which is still waiting for a worker is
  * already evidence that the driver is the bottleneck, and §7.2 declares the run invalid above
  * 250 ms. A per-unit `startDelay` only reports that after the unit finally runs.
  *
  * One instance per scenario, matching the `scenario` label on the gauge.
  *
  * **What track F should read.** Poll [[current]] on the metric-collection tick and set the
  * gauge from it; it needs no arguments and reads the clock itself. The driver loop (track I)
  * owns the other side: call [[observeHead]] with the head arrival's `intendedStart` whenever
  * the queue's head changes, and with `None` when the queue drains, which reports zero lag
  * rather than a stale value. Deliberately a `Ref` of one `Instant` and not a counter of
  * anything: the gauge must be readable at any instant without the collector touching the
  * scheduler's queue.
  */
trait ScheduleLag:
  /** @param intendedStart head of the scheduled queue, or `None` when nothing is queued. */
  def observeHead(intendedStart: Option[Instant]): UIO[Unit]

  def headIntendedStart: UIO[Option[Instant]]

  /** Lag against a caller-supplied `now`. The seam that lets the tests assert exact values
    * without a `TestClock`.
    */
  def lagAt(now: Instant): UIO[Duration]

  /** Lag against the current clock -- what track F's collector calls. */
  def current: UIO[Duration]

object ScheduleLag:
  val make: UIO[ScheduleLag] = Ref.make(Option.empty[Instant]).map(RefScheduleLag(_))

private final class RefScheduleLag(head: Ref[Option[Instant]]) extends ScheduleLag:
  def observeHead(intendedStart: Option[Instant]): UIO[Unit] = head.set(intendedStart)

  def headIntendedStart: UIO[Option[Instant]] = head.get

  def lagAt(now: Instant): UIO[Duration] =
    head.get.map:
      case Some(intendedStart) => IntendedStart.nonNegativeBetween(intendedStart, now)
      case None => Duration.Zero

  def current: UIO[Duration] = Clock.instant.flatMap(lagAt)

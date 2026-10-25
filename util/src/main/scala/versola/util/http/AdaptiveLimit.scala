package versola.util.http

import zio.*

/** The bounds and gains of an [[AdaptiveLimit]].
  *
  * @param minLimit
  *   the limit never goes below this, whatever the signals say: a floor that keeps a pod serving something
  * @param maxLimit
  *   the limit never goes above this; also what the limit starts at when nothing has been learned yet
  * @param tolerance
  *   how much slower than its long-run level a call may get before the limit starts to shrink (1.5: half again)
  * @param smoothing
  *   the share of each new estimate that replaces the old limit (0.2: a fifth), so one noisy window does not swing it
  * @param lagTarget
  *   how late the executor may run a timer before it counts as a stall; longer than this, the limit shrinks no matter what
  *   the completed calls looked like (a stalled pod completes no calls to measure)
  */
final case class AdaptiveSettings(
    minLimit: Int,
    maxLimit: Int,
    tolerance: Double,
    smoothing: Double,
    lagTarget: Duration,
)

object AdaptiveSettings:
  val default: AdaptiveSettings =
    AdaptiveSettings(minLimit = 16, maxLimit = 1024, tolerance = 1.5, smoothing = 0.2, lagTarget = 50.millis)

/** What one window (a second) of traffic looked like, for one class of request.
  *
  * @param meanRtt
  *   the mean time the window's completed calls took, in nanoseconds; meaningless when `completed` is 0
  * @param completed
  *   how many calls completed in the window
  */
final case class RttWindow(meanRtt: Double, completed: Int)

/** What the limiter has learned.
  *
  * @param limit
  *   the limit in force
  * @param longRtt
  *   the long-run level of each class's call time
  * @param windows
  *   how many windows have been seen
  * @param envelope
  *   the in-flight peak the pod has recently carried while healthy, decaying over about a minute (see [[AdaptiveLimit.step]])
  * @param rttSlow
  *   whether the previous window's call times were beyond the tolerance (one slow window alone does not shrink the limit)
  */
final case class AdaptiveState(limit: Double, longRtt: Vector[Double], windows: Int, envelope: Double, rttSlow: Boolean)

/** A concurrency limit that follows the service instead of a number someone guessed.
  *
  * The idea is TCP's: let the limit grow while calls stay as fast as they have been, and shrink it when they slow down,
  * because calls slow down when the work queued behind the CPU, the pool or a dependency exceeds what the pod can do.
  * Netflix's Gradient2 (the `concurrency-limits` library) estimates it as
  * `limit * gradient + queue`, where `gradient = clamp(0.5, 1, tolerance * longRtt / shortRtt)`, smoothed.
  * Written here rather than taken from the library to fit the project's metrics and dynamic settings, and extended in two
  * ways:
  *
  *   - **Per class.** A new login (a password hash) and a proxied call differ by an order of magnitude in time, so the mix
  *     moving between them would read as slowing down. Each class keeps its own long-run level; the gradient is the worst
  *     of them.
  *   - **A second signal, the executor lag.** A pod that stalls completes nothing, so there is no call time to see. The lag
  *     of a timer on the executor is that stall seen directly; above its target the gradient follows it.
  *
  * and bounded from below by what the pod has just shown it can carry (see [[HeadroomFactor]]), so that the ordinary
  * second-to-second variation of the in-flight count does not cross the limit.
  *
  * Pure: [[step]] takes the state and a window and returns the next state, so it is tested without a clock or a service.
  */
object AdaptiveLimit:
  /** A call-time level older than this many windows is forgotten at the rate 1/this: about two minutes of one-second windows. */
  val LongWindow: Int = 120

  /** Until this many windows have been seen the long-run level is a plain average: a start-up that is not yet "normal". */
  private val Warmup: Int = 10

  private val DecreaseSmoothing: Double = 0.5

  /** The envelope forgets at the rate 1/this per window (a one-second window): its time constant is a minute. */
  val EnvelopeWindows: Int = 60

  /** While a window is healthy the limit is at least this many times the envelope. Not configurable: a limit under 1.5 times
    * the concurrency the pod carried a moment ago refused requests of a healthy pod, over 1.5 is room that was never used.
    */
  val HeadroomFactor: Double = 1.5

  /** The least the limit may be on a healthy window: [[HeadroomFactor]] times the envelope, never above `maxLimit`. */
  def headroom(settings: AdaptiveSettings, state: AdaptiveState): Double =
    (HeadroomFactor * state.envelope).min(settings.maxLimit)

  /** Starts at `start` (what the operator set as the limit before adaptation), inside the bounds, and learns from there. */
  def initial(settings: AdaptiveSettings, classes: Int, start: Int): AdaptiveState =
    AdaptiveState(
      limit = start.max(settings.minLimit).min(settings.maxLimit).toDouble,
      longRtt = Vector.fill(classes)(0.0),
      windows = 0,
      envelope = 0.0,
      rttSlow = false,
    )

  /** A new state keeps what was learned if it is still inside the bounds, and is clamped to them if not. */
  def rebound(state: AdaptiveState, settings: AdaptiveSettings): AdaptiveState =
    state.copy(limit = state.limit.max(settings.minLimit).min(settings.maxLimit))

  /** One window.
    *
    * Two rules on top of the gradient:
    *
    *   - **Headroom.** `envelope = max(peak, envelope * (1 - 1/EnvelopeWindows))`, where `peak` counts only if the window was
    *     healthy (a pile-up during a stall is a queue, not what the pod carries) and `peak * HeadroomFactor < limit` (a peak
    *     pinned at the limit is the limit's own doing; folding it in would let the limit lift itself by 1.5 every second).
    *     A healthy window then never ends with a limit under `HeadroomFactor * envelope`. The floor only restores a limit
    *     that was once in force, growth beyond it is still the slow probe. A degraded window is not lifted: the decrease may
    *     take the limit below the floor, so a stall still shrinks it quickly.
    *   - **A slow window must repeat.** Call times beyond the tolerance for one window (a GC pause) hold the limit; the
    *     second window in a row decreases it. Not so for the executor lag, which acts at once: it is the stall itself.
    *
    * @param peakInFlight
    *   the most calls in flight at once in the window; a limit that was never reached says nothing about the pod, so it is
    *   not raised on that evidence
    * @param lag
    *   the worst lateness of the executor probe in the window
    */
  def step(
      settings: AdaptiveSettings,
      state: AdaptiveState,
      windows: Vector[RttWindow],
      peakInFlight: Int,
      lag: Duration,
  ): AdaptiveState =
    val seen = state.windows + 1

    val longs = state.longRtt.zip(windows).map: (long, w) =>
      if w.completed == 0 || w.meanRtt <= 0 then long
      else if long == 0 then w.meanRtt
      else
        val updated =
          if seen <= Warmup then long + (w.meanRtt - long) / seen
          else long * (1 - 1.0 / LongWindow) + w.meanRtt / LongWindow
        // A long-run level far above what calls take now was learned during a bad stretch: let it come down faster.
        if updated > 2 * w.meanRtt then updated * 0.95 else updated

    val rttGradient = state.longRtt.zip(windows).zip(longs).collect {
      case ((_, w), long) if w.completed > 0 && w.meanRtt > 0 && long > 0 =>
        (settings.tolerance * long / w.meanRtt).max(0.5).min(1.0)
    }.minOption.getOrElse(1.0)

    val lagGradient =
      if lag > settings.lagTarget then (settings.lagTarget.toNanos.toDouble / lag.toNanos.toDouble).max(0.5).min(1.0) else 1.0

    val rttSlow = rttGradient < 1.0
    val healthy = !rttSlow && lagGradient >= 1.0
    // the first slow window alone is held, not acted on; a lag has no such grace
    val confirmedRtt = if state.rttSlow then rttGradient else 1.0
    val gradient = confirmedRtt.min(lagGradient)
    val appLimited = peakInFlight < state.limit / 2
    val unchanged = (gradient >= 1.0 && appLimited) || (rttSlow && !state.rttSlow && lagGradient >= 1.0)
    val adapted =
      if unchanged then state.limit
      else
        // `queue`: the room to probe upward by, which grows with the limit but slowly (Gradient2's square root).
        val estimate = state.limit * gradient + math.sqrt(state.limit).max(1)
        // Asymmetric, like TCP's additive increase and multiplicative decrease: the limit follows a worsening quickly (at
        // least half of each estimate), and an improvement at `smoothing`. A window here is a second, not a handful of calls.
        val rate = if estimate < state.limit then settings.smoothing.max(DecreaseSmoothing) else settings.smoothing
        state.limit * (1 - rate) + estimate * rate

    val decayed = state.envelope * (1 - 1.0 / EnvelopeWindows)
    val envelope =
      if healthy && peakInFlight * HeadroomFactor < state.limit then decayed.max(peakInFlight.toDouble) else decayed
    val raised = if healthy then adapted.max(headroom(settings, state.copy(envelope = envelope))) else adapted
    state.copy(
      limit = raised.max(settings.minLimit).min(settings.maxLimit),
      longRtt = longs,
      windows = seen,
      envelope = envelope,
      rttSlow = rttSlow,
    )

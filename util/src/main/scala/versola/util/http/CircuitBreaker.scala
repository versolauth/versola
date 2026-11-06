package versola.util.http

import zio.*

/** When a peer's circuit opens and for how long.
  *
  * @param failureRatio
  *   the share of failed calls in the window at which the circuit opens
  * @param minCalls
  *   fewer calls than this in the window never open it: two failures out of three is not evidence
  * @param window
  *   how far back calls count
  * @param openFor
  *   how long calls are refused before probes are let through
  * @param probeCalls
  *   how many probes are let through at once, and how many must succeed in a row for the circuit to close
  */
final case class BreakerSettings(
    enabled: Boolean,
    failureRatio: Double,
    minCalls: Int,
    window: Duration,
    openFor: Duration,
    probeCalls: Int,
)

object BreakerSettings:
  val default: BreakerSettings =
    BreakerSettings(enabled = false, failureRatio = 0.5, minCalls = 20, window = 10.seconds, openFor = 5.seconds, probeCalls = 3)

/** What a call finds at the circuit. */
private[http] enum Permit:
  /** The circuit is closed: go ahead, and report how it went. */
  case Closed

  /** The circuit is half open and this call is one of its probes: go ahead, and report how it went, or give the place back. */
  case Probe

  /** The circuit is open, or its probes are out. */
  case Refused

/** A circuit breaker for one peer: closed while calls succeed, open when a given share of them fail, then half open to try the
  * peer again with a few probes before closing.
  *
  * What a breaker is for here: a peer that is down answers every call with a timeout, and each of those calls holds a
  * connection, a fiber and a place of the guard for the whole timeout. Refusing them at once frees those and lets the peer
  * recover without the load of the callers' retries. It does not cure a pod that is itself stalled.
  *
  * Plain and synchronized: it is a few integer updates per call, and time is passed in so a test needs no clock.
  */
private[http] final class CircuitBreaker:
  import CircuitBreaker.*

  private var state: State = State.Closed
  private var openUntil: Long = 0L
  private var probesStarted = 0
  private var probesSucceeded = 0
  private val calls = new Array[Int](Buckets)
  private val failures = new Array[Int](Buckets)
  private var lastBucket = -1L

  /** 0 closed, 1 half open, 2 open, as published. */
  def stateCode: Int = synchronized:
    state match
      case State.Closed => 0
      case State.HalfOpen => 1
      case State.Open => 2

  /** Whether a call may go, at `now` (nanoseconds). */
  def acquire(now: Long, settings: BreakerSettings): Permit = synchronized:
    state match
      case State.Closed => Permit.Closed
      case State.Open =>
        if now >= openUntil then
          state = State.HalfOpen
          probesStarted = 0
          probesSucceeded = 0
          takeProbe(settings)
        else Permit.Refused
      case State.HalfOpen => takeProbe(settings)

  private def takeProbe(settings: BreakerSettings): Permit =
    if probesStarted < settings.probeCalls then
      probesStarted += 1
      Permit.Probe
    else Permit.Refused

  /** Reports the outcome of a call that was let through. */
  def record(now: Long, settings: BreakerSettings, permit: Permit, failed: Boolean): Unit = synchronized:
    permit match
      case Permit.Probe =>
        if state == State.HalfOpen then
          if failed then open(now, settings)
          else
            probesSucceeded += 1
            if probesSucceeded >= settings.probeCalls then close()
      case Permit.Closed =>
        // a call that started while closed and ended after the circuit opened tells nothing new
        if state == State.Closed then
          val i = bucket(now, settings)
          calls(i) += 1
          if failed then failures(i) += 1
          val total = calls.sum
          if total >= settings.minCalls && failures.sum.toDouble / total >= settings.failureRatio then open(now, settings)
      case Permit.Refused => ()

  /** A probe that ended without an answer (interrupted): its place goes back so the circuit is not left waiting for it. */
  def abandon(permit: Permit): Unit = synchronized:
    if permit == Permit.Probe && state == State.HalfOpen && probesStarted > 0 then probesStarted -= 1

  private def open(now: Long, settings: BreakerSettings): Unit =
    state = State.Open
    openUntil = now + settings.openFor.toNanos

  private def close(): Unit =
    state = State.Closed
    java.util.Arrays.fill(calls, 0)
    java.util.Arrays.fill(failures, 0)
    lastBucket = -1L

  /** The bucket for `now`, with the buckets that went by since the last call cleared. */
  private def bucket(now: Long, settings: BreakerSettings): Int =
    val width = math.max(1L, settings.window.toNanos / Buckets)
    val current = now / width
    if lastBucket < 0 || current - lastBucket >= Buckets then
      java.util.Arrays.fill(calls, 0)
      java.util.Arrays.fill(failures, 0)
    else
      var b = lastBucket + 1
      while b <= current do
        val i = (b % Buckets).toInt
        calls(i) = 0
        failures(i) = 0
        b += 1
    lastBucket = current
    (current % Buckets).toInt

private[http] object CircuitBreaker:
  private val Buckets = 10

  private enum State:
    case Closed, Open, HalfOpen

/** What a service may spend on retries: a fraction of the calls it makes, not a count per call. Without a cap, a peer that
  * fails turns every caller's retry into extra load exactly when it can least carry it; with one, the load a failure adds
  * is bounded by `ratio` (Google's SRE book: 10%) however many calls fail.
  *
  * A call deposits `ratio` of a token, a retry spends one, and a trickle of `minPerSecond` keeps a quiet service able to
  * retry at all.
  */
final case class RetryBudgetSettings(ratio: Double, minPerSecond: Double)

object RetryBudgetSettings:
  val default: RetryBudgetSettings = RetryBudgetSettings(ratio = 0.1, minPerSecond = 5.0)

private[http] final class RetryBudget:
  private var tokens = 0.0
  private var last = -1L

  /** A call is made. */
  def deposit(now: Long, settings: RetryBudgetSettings): Unit = synchronized:
    refill(now, settings)
    tokens = math.min(cap(settings), tokens + settings.ratio)

  /** Whether a retry may be made; spends a token if so. */
  def tryWithdraw(now: Long, settings: RetryBudgetSettings): Boolean = synchronized:
    refill(now, settings)
    if tokens >= 1.0 then
      tokens -= 1.0
      true
    else false

  private def cap(settings: RetryBudgetSettings): Double = math.max(10.0, settings.minPerSecond * 10)

  private def refill(now: Long, settings: RetryBudgetSettings): Unit =
    if last >= 0 && now > last then tokens = math.min(cap(settings), tokens + settings.minPerSecond * (now - last) / 1e9)
    last = now

package versola.util.http

import zio.*
import zio.http.*
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.metrics.{Metric, MetricLabel}

import java.util.concurrent.ConcurrentHashMap
import scala.collection.immutable.Queue
import scala.concurrent.duration.Duration as ScalaDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NoStackTrace

/** What the guard does with an outbound call:
  *
  *   - `Off`: nothing, the call goes straight to the client.
  *   - `Shadow`: calls are counted, timed and queued exactly as before (no limit on the wait, no timeout), and what
  *     `Enforce` *would* have rejected is counted. Behaviour does not change, so this is the mode to tune in.
  *   - `Enforce`: a call that cannot start within `queueTimeout`, or that finds `maxQueue` calls already waiting, is
  *     rejected at once, and a call that has not answered within `requestTimeout` is abandoned.
  */
enum GuardMode:
  case Off, Shadow, Enforce

object GuardMode:
  def parse(value: String): Option[GuardMode] =
    value.trim.toLowerCase match
      case "off" => Some(Off)
      case "shadow" => Some(Shadow)
      case "enforce" => Some(Enforce)
      case _ => None

/** The settings of an [[OutboundGuard]]; they are replaced as a whole and take effect on the next call.
  *
  * @param maxConcurrent
  *   calls in flight towards one peer. Defaults to the connection pool's size: the pool already holds this many
  *   connections, so a larger number only moves the queue from here into the pool.
  * @param maxQueue
  *   calls allowed to wait for a place; one more is rejected without waiting (`Enforce` only)
  * @param queueTimeout
  *   the longest a call waits for a place (`Enforce` only)
  * @param requestTimeout
  *   the longest a call may take from the moment it starts to the response headers (`Enforce` only; the body is bounded
  *   by the client's own `bodyReadTimeout`)
  * @param breaker
  *   the circuit breaker of each peer, see [[CircuitBreaker]]; off unless `enabled`
  * @param retryBudget
  *   what [[OutboundGuard.retrying]] may spend on retries
  */
final case class OutboundGuardSettings(
    mode: GuardMode,
    maxConcurrent: Int,
    maxQueue: Int,
    queueTimeout: Duration,
    requestTimeout: Duration,
    breaker: BreakerSettings = BreakerSettings.default,
    retryBudget: RetryBudgetSettings = RetryBudgetSettings.default,
)

object OutboundGuardSettings:
  val defaultMaxQueue: Int = 256
  val defaultQueueTimeout: Duration = 1.second
  val defaultRequestTimeout: Duration = 10.seconds

  /** Resolves the settings from their raw values (`None` when unset). An invalid value falls back to its default, so a
    * typo cannot take the service down. Pure, to be tested without the environment.
    */
  def parse(
      mode: Option[String],
      maxConcurrent: Option[String],
      maxQueue: Option[String],
      queueTimeout: Option[String],
      requestTimeout: Option[String],
      poolSize: Int,
  ): OutboundGuardSettings =
    def duration(raw: Option[String], default: Duration): Duration =
      raw.flatMap(v => Try(ScalaDuration(v.trim)).toOption)
        .filter(d => d.isFinite && d > ScalaDuration.Zero)
        .map(Duration.fromScala)
        .getOrElse(default)
    def positive(raw: Option[String], default: Int): Int =
      raw.flatMap(_.trim.toIntOption).filter(_ > 0).getOrElse(default)
    OutboundGuardSettings(
      mode = mode.flatMap(GuardMode.parse).getOrElse(GuardMode.Shadow),
      maxConcurrent = positive(maxConcurrent, poolSize),
      maxQueue = positive(maxQueue, defaultMaxQueue),
      queueTimeout = duration(queueTimeout, defaultQueueTimeout),
      requestTimeout = duration(requestTimeout, defaultRequestTimeout),
    )

/** A call the guard refused because the peer had no room for it. Answered `503` with `Retry-After`. */
final class OutboundOverloaded(val peer: String, val reason: String)
  extends RuntimeException(s"outbound call to $peer rejected: $reason")
     with NoStackTrace

/** A call the guard abandoned because the peer did not answer in time. Answered `504`. */
final class OutboundTimedOut(val peer: String, val after: Duration)
  extends RuntimeException(s"outbound call to $peer timed out after ${after.render}")
     with NoStackTrace

/** A counting limiter whose limit can change while calls are in it, with a bounded queue and a wait that can be cut short.
  * `ZIO.Semaphore` has neither a bounded queue nor a resizable limit, and both are what an adaptive limit needs.
  */
private[http] final class Limiter(initialLimit: Int):
  import Limiter.*

  private val state: Ref[State] = Unsafe.unsafe(u ?=> Ref.unsafe.make(State(initialLimit, 0, Queue.empty))(using u))

  def snapshot: UIO[(inFlight: Int, waiting: Int, limit: Int)] =
    state.get.map(s => (s.inFlight, s.waiters.size, s.limit))

  /** Takes a place, waiting if there is none. Must run uninterruptibly, `restore` being the mask's way to let the wait
    * (and only the wait) be interrupted: a place that was handed over cannot then be lost between being granted and
    * being used. On success the caller owns a place and must [[release]] it.
    */
  def acquire(
      restore: ZIO.InterruptibilityRestorer,
      maxQueue: Int,
      timeout: Option[Duration],
  ): IO[Rejection, Unit] =
    Promise.make[Nothing, Unit].flatMap: promise =>
      val waiter = Waiter(promise)
      state
        .modify: s =>
          if s.inFlight < s.limit then (Admission.Immediate, s.copy(inFlight = s.inFlight + 1))
          else if s.waiters.size >= maxQueue then (Admission.Full, s)
          else (Admission.Queued, s.copy(waiters = s.waiters.enqueue(waiter)))
        .flatMap:
          case Admission.Immediate => ZIO.unit
          case Admission.Full => ZIO.fail(Rejection.QueueFull)
          case Admission.Queued =>
            val await = timeout.fold(promise.await.map(Some(_)))(promise.await.timeout(_))
            restore(await)
              .onInterrupt(abandon(waiter).flatMap(stillQueued => release.unless(stillQueued).unit))
              .flatMap:
                case Some(_) => ZIO.unit
                // Timed out. If a releaser handed the place over in the same instant, it is ours after all.
                case None => abandon(waiter).flatMap(stillQueued => ZIO.fail(Rejection.QueueTimeout).when(stillQueued).unit)

  /** Hands the place to the oldest waiter, or frees it. A place above a lowered limit is freed rather than handed on. */
  def release: UIO[Unit] =
    state
      .modify: s =>
        if s.inFlight > s.limit || s.waiters.isEmpty then (None, s.copy(inFlight = s.inFlight - 1))
        else
          val (next, rest) = s.waiters.dequeue
          (Some(next), s.copy(waiters = rest))
      .flatMap(_.fold(ZIO.unit)(_.granted.succeed(()).unit))

  def setLimit(limit: Int): UIO[Unit] =
    state
      .modify: s =>
        var next = s.copy(limit = limit)
        val woken = List.newBuilder[Waiter]
        while next.inFlight < next.limit && next.waiters.nonEmpty do
          val (waiter, rest) = next.waiters.dequeue
          woken += waiter
          next = next.copy(inFlight = next.inFlight + 1, waiters = rest)
        (woken.result(), next)
      .flatMap(ZIO.foreachDiscard(_)(_.granted.succeed(())))

  /** Takes the waiter out of the queue; `true` if it was still there, `false` if a place had already been handed to it. */
  private def abandon(waiter: Waiter): UIO[Boolean] =
    state.modify: s =>
      if s.waiters.exists(_.eq(waiter)) then (true, s.copy(waiters = s.waiters.filterNot(_.eq(waiter))))
      else (false, s)

private[http] object Limiter:
  private final class Waiter(val granted: Promise[Nothing, Unit])
  private final case class State(limit: Int, inFlight: Int, waiters: Queue[Waiter])
  private enum Admission:
    case Immediate, Full, Queued

  enum Rejection:
    case QueueFull, QueueTimeout

/** Bounded, observable outbound calls: a limit per peer, a bounded queue and timeouts, all of them changeable while the
  * process runs ([[update]]).
  *
  * Why it exists: zio-http's pool makes a caller wait for a connection with no timeout, and nothing bounded the call
  * itself, so a peer that stopped answering turned into a queue of a thousand calls per pod that nothing ended. Every
  * outbound call of a service goes through the shared client, so this is the one place to bound them.
  *
  * Metrics, per `peer`: `http_client_guard_in_flight`, `http_client_guard_waiting`, `http_client_guard_limit`,
  * `http_client_guard_wait_seconds`, `http_client_guard_breaker_state` (0 closed, 1 half open, 2 open),
  * `http_client_retry_budget_denied_total`, and `http_client_guard_rejected_total` by `reason` and `mode` (in `shadow` it
  * counts what `enforce` would have rejected).
  */
final class OutboundGuard private (settings: Ref[OutboundGuardSettings]):
  import OutboundGuard.*

  private val limiters = new ConcurrentHashMap[String, Limiter]()
  private val breakers = new ConcurrentHashMap[String, CircuitBreaker]()
  private val budgets = new ConcurrentHashMap[String, RetryBudget]()

  // Until the service has built its dependencies nothing is enforced, whatever the settings say: what a service waits for at
  // start-up (central's configuration) must not be refused by the very settings it is about to receive. Armed once, with the
  // peers that are never guarded: the services the process cannot work without (central).
  private val armed = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val exempt = new java.util.concurrent.atomic.AtomicReference(Set.empty[String])

  /** From now on `Enforce` is enforced, except towards `exemptPeers` (`host:port`), which the guard leaves alone. */
  def arm(exemptPeers: Set[String]): UIO[Unit] = ZIO.succeed:
    exempt.set(exemptPeers)
    armed.set(true)

  def current: UIO[OutboundGuardSettings] = settings.get

  /** Replaces the settings. A changed `maxConcurrent` is applied to the peers already known. */
  def update(next: OutboundGuardSettings): UIO[Unit] =
    settings.set(next) *> ZIO.foreachDiscard(limiters.asScala.toList): (peer, limiter) =>
      limiter.setLimit(next.maxConcurrent) *> publish(peer, limiter)

  private def limiterFor(peer: String, limit: Int): Limiter =
    limiters.computeIfAbsent(peer, _ => Limiter(limit))

  private def publish(peer: String, limiter: Limiter): UIO[Unit] =
    limiter.snapshot.flatMap: s =>
      val tag = Set(MetricLabel("peer", peer))
      inFlightGauge.tagged(tag).set(s.inFlight.toDouble) *>
        waitingGauge.tagged(tag).set(s.waiting.toDouble) *>
        limitGauge.tagged(tag).set(s.limit.toDouble)

  private def reject(peer: String, reason: String, mode: GuardMode): UIO[Unit] =
    rejected.tagged(Set(MetricLabel("peer", peer), MetricLabel("reason", reason), MetricLabel("mode", mode.toString.toLowerCase))).increment

  /** Runs `call` (an outbound call to `peer`) under the guard. `failed` says whether a successful result is still a failure of
    * the peer for the circuit breaker (a `502`, `503` or `504`).
    */
  def protect[R, E >: Throwable, A](peer: String, failed: A => Boolean = (_: A) => false)(call: ZIO[R, E, A]): ZIO[R, E, A] =
    settings.get.flatMap: s =>
      if s.mode == GuardMode.Off || exempt.get.contains(peer) then call
      else
        // nothing is refused or abandoned before the service is up
        val mode = if s.mode == GuardMode.Enforce && !armed.get then GuardMode.Shadow else s.mode
        val limiter = limiterFor(peer, s.maxConcurrent)
        val enforce = mode == GuardMode.Enforce
        ZIO.uninterruptibleMask: restore =>
          for
            now <- Clock.nanoTime
            permit <- circuit(peer, s, mode, now)
            arrived <- limiter.snapshot
            _ <- reject(peer, "queue_full", mode).when(!enforce && arrived.inFlight >= arrived.limit && arrived.waiting >= s.maxQueue)
            startedWaiting <- Clock.nanoTime
            _ <- limiter
              .acquire(restore, if enforce then s.maxQueue else Int.MaxValue, if enforce then Some(s.queueTimeout) else None)
              .tapError(r =>
                reject(peer, if r == Limiter.Rejection.QueueFull then "queue_full" else "queue_timeout", mode) *> publish(peer, limiter) *>
                  giveBack(peer, permit),
              )
              .mapError(r => OutboundOverloaded(peer, if r == Limiter.Rejection.QueueFull then "queue full" else "queue timeout"))
            granted <- Clock.nanoTime
            waited = Duration.fromNanos(granted - startedWaiting)
            _ <- waitSeconds.tagged(Set(MetricLabel("peer", peer))).update(waited.toNanos / 1e9)
            _ <- reject(peer, "queue_timeout", mode).when(!enforce && waited > s.queueTimeout)
            _ <- publish(peer, limiter)
            result <- restore(timed(peer, s, mode, call))
              .onExit(exit => outcome(peer, s, permit, exit, failed))
              .ensuring(limiter.release *> publish(peer, limiter))
          yield result

  /** What the circuit of `peer` says about this call: let it go (and report how it went), or refuse it (in `enforce`). */
  private def circuit(peer: String, s: OutboundGuardSettings, mode: GuardMode, now: Long): IO[OutboundOverloaded, Permit] =
    // `Refused` doubles as "not in the circuit's accounting": breaker off, or a shadow call it would have refused
    if !s.breaker.enabled then ZIO.succeed(Permit.Refused)
    else
      val breaker = breakerFor(peer)
      val permit = breaker.acquire(now, s.breaker)
      publishBreaker(peer, breaker) *> (
        if permit != Permit.Refused then ZIO.succeed(permit)
        else
          reject(peer, "circuit_open", mode) *>
            (if mode == GuardMode.Enforce then ZIO.fail(OutboundOverloaded(peer, "circuit open"))
             // shadow: the call goes through, outside the circuit's accounting
             else ZIO.succeed(Permit.Refused))
      )

  /** Reports the end of a call to the circuit: a failure of the peer is a transport error, a timeout, or a result `failed` calls one. */
  private def outcome[E, A](peer: String, s: OutboundGuardSettings, permit: Permit, exit: Exit[E, A], failed: A => Boolean): UIO[Unit] =
    if !s.breaker.enabled || permit == Permit.Refused then ZIO.unit
    else
      val breaker = breakerFor(peer)
      Clock.nanoTime.flatMap: now =>
        ZIO.succeed:
          exit match
            case Exit.Success(a) => breaker.record(now, s.breaker, permit, failed(a))
            case Exit.Failure(cause) if cause.isInterruptedOnly => breaker.abandon(permit)
            case Exit.Failure(_) => breaker.record(now, s.breaker, permit, failed = true)
        *> publishBreaker(peer, breaker)

  private def giveBack(peer: String, permit: Permit): UIO[Unit] = ZIO.succeed(breakerFor(peer).abandon(permit))

  private def breakerFor(peer: String): CircuitBreaker = breakers.computeIfAbsent(peer, _ => CircuitBreaker())
  private def budgetFor(peer: String): RetryBudget = budgets.computeIfAbsent(peer, _ => RetryBudget())

  private def publishBreaker(peer: String, breaker: CircuitBreaker): UIO[Unit] =
    breakerGauge.tagged(Set(MetricLabel("peer", peer))).set(breaker.stateCode.toDouble)

  /** Runs `call`, and again when it fails with an error `retryable` calls transient, up to `maxRetries` times, for as long as
    * the peer's retry budget allows. When the budget is spent the failure is returned instead of retried (in `shadow` the
    * retry still happens and the denial is only counted). The retries are not delayed here: put the delay in `call`, or
    * retry something that already waits.
    */
  def retrying[R, E, A](peer: String, maxRetries: Int = 2)(retryable: E => Boolean)(call: ZIO[R, E, A]): ZIO[R, E, A] =
    settings.get.flatMap: s =>
      def attempt(n: Int): ZIO[R, E, A] =
        call.catchSome:
          case e if n < maxRetries && retryable(e) =>
            Clock.nanoTime.flatMap: now =>
              ZIO.succeed(budgetFor(peer).tryWithdraw(now, s.retryBudget)).flatMap: allowed =>
                if allowed || s.mode != GuardMode.Enforce then
                  retryDenied.tagged(Set(MetricLabel("peer", peer), MetricLabel("mode", s.mode.toString.toLowerCase))).increment
                    .unless(allowed) *> attempt(n + 1)
                else
                  retryDenied.tagged(Set(MetricLabel("peer", peer), MetricLabel("mode", "enforce"))).increment *> ZIO.fail(e)
      if s.mode == GuardMode.Off then call
      else
        Clock.nanoTime.flatMap(now => ZIO.succeed(budgetFor(peer).deposit(now, s.retryBudget))) *> attempt(0)

  private def timed[R, E >: Throwable, A](peer: String, s: OutboundGuardSettings, mode: GuardMode, call: ZIO[R, E, A]): ZIO[R, E, A] =
    if mode == GuardMode.Enforce then
      call.timeoutFail(OutboundTimedOut(peer, s.requestTimeout))(s.requestTimeout)
        .tapError(e => reject(peer, "request_timeout", mode).when((e: Any).isInstanceOf[OutboundTimedOut]))
    else
      call.timed.flatMap: (took, a) =>
        reject(peer, "request_timeout", mode).when(took > s.requestTimeout).as(a)

  /** The guard as a client aspect. It wraps the whole call, so a rejection is cheap: no span, no log line, no pool. */
  val aspect: ZClientAspect[Nothing, Any, Nothing, Body, Throwable, Any, Nothing, Response] =
    new ZClientAspect[Nothing, Any, Nothing, Body, Throwable, Any, Nothing, Response]:
      def apply[ReqEnv, Env <: Any, In <: Body, Err >: Throwable, Out <: Response](
          client: ZClient[Env, ReqEnv, In, Err, Out],
      ): ZClient[Env, ReqEnv, In, Err, Out] =
        client.transform(
          client.bodyEncoder,
          client.bodyDecoder,
          new ZClient.Driver[Env, ReqEnv, Err]:
            def request(
                version: Version,
                method: Method,
                url: URL,
                headers: Headers,
                body: Body,
                sslConfig: Option[ClientSSLConfig],
                proxy: Option[Proxy],
            )(using trace: Trace): ZIO[Env & ReqEnv, Err, Response] =
              val peer = peerOf(url)
              protect(peer, failedByStatus)(client.driver.request(version, method, url, headers, body, sslConfig, proxy))

            def socket[Env1 <: Env](version: Version, url: URL, headers: Headers, app: WebSocketApp[Env1])(using
                trace: Trace,
                ev: ReqEnv =:= Scope,
            ): ZIO[Env1 & ReqEnv, Err, Response] =
              client.driver.socket(version, url, headers, app),
        )

object OutboundGuard:
  /** The peer a URL names, as the guard keys its limits and metrics: `host:port`. */
  def peerOf(url: URL): String = url.kind match
    case loc: URL.Location.Absolute => s"${loc.host}:${loc.port}"
    case URL.Location.Relative => "unknown"

  private val waitBoundaries: Boundaries =
    Boundaries.fromChunk(Chunk(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0))

  private val inFlightGauge = Metric.gauge("http_client_guard_in_flight")
  private val waitingGauge = Metric.gauge("http_client_guard_waiting")
  private val limitGauge = Metric.gauge("http_client_guard_limit")
  private val waitSeconds = Metric.histogram("http_client_guard_wait_seconds", waitBoundaries)
  private val rejected = Metric.counter("http_client_guard_rejected_total")
  private val breakerGauge = Metric.gauge("http_client_guard_breaker_state")
  private val retryDenied = Metric.counter("http_client_retry_budget_denied_total")

  /** A response that says the peer, not the request, is at fault. */
  private val failedByStatus: Response => Boolean = r =>
    r.status == Status.BadGateway || r.status == Status.ServiceUnavailable || r.status == Status.GatewayTimeout

  def make(initial: OutboundGuardSettings): UIO[OutboundGuard] =
    Ref.make(initial).map(OutboundGuard(_))

  private def env(name: String): Option[String] = Option(java.lang.System.getenv(name))

  /** The settings the process starts with: `HTTP_CLIENT_GUARD_MODE` (`off`, `shadow` -- the default -- or `enforce`),
    * `HTTP_CLIENT_GUARD_MAX_CONCURRENT` (default: the pool size), `HTTP_CLIENT_GUARD_MAX_QUEUE`,
    * `HTTP_CLIENT_GUARD_QUEUE_TIMEOUT` and `HTTP_CLIENT_GUARD_REQUEST_TIMEOUT`.
    */
  def initialSettings(poolSize: Int): OutboundGuardSettings =
    OutboundGuardSettings.parse(
      env("HTTP_CLIENT_GUARD_MODE"),
      env("HTTP_CLIENT_GUARD_MAX_CONCURRENT"),
      env("HTTP_CLIENT_GUARD_MAX_QUEUE"),
      env("HTTP_CLIENT_GUARD_QUEUE_TIMEOUT"),
      env("HTTP_CLIENT_GUARD_REQUEST_TIMEOUT"),
      poolSize,
    )

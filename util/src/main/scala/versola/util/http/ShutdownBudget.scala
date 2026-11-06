package versola.util.http

import zio.*

import java.util.concurrent.atomic.AtomicLong

/** What is left of the shutdown hook's time for the finalizer that runs now.
  *
  * [[VersolaApp.GracefulShutdownTimeout]] bounds the whole shutdown, and the finalizers run one after another inside it. Each
  * bounds its own last drain with a number of its own (a back-channel outbox 10 s, the audit queue 1 s + 5 s, the antifraud events,
  * lists and counters 5 s each: 31 s in `auth` against 15 s), so when the database is down, which is when every drain waits its full
  * time, the hook gives up part-way and the drains after it never run. Summing a number per finalizer has to be redone for each one
  * that is added; a shared deadline does not.
  *
  * [[begin]] is the first thing the shutdown does (it is registered last, so it runs first); a drain then waits for at most
  * `min(its own bound, what is left)`. The drains keep the order they already had, so the ones that run first get their whole bound
  * and the last ones what remains, down to nothing: a drain with no time left does not start.
  *
  * Without [[begin]], which is how every unit spec and a service that was not started through [[VersolaApp]] runs, [[allowed]]
  * returns the drain's own bound unchanged.
  */
object ShutdownBudget:

  /** Left unspent: the JVM needs to flush and halt after the finalizers, and a timer is not exact. */
  val Margin: Duration = 2.seconds

  /** The time the finalizers share. */
  val Total: Duration = VersolaApp.GracefulShutdownTimeout.minus(Margin)

  private val NotBegun = Long.MinValue

  private val startedAt = new AtomicLong(NotBegun)

  /** Starts the clock, once; a second call changes nothing. */
  val begin: UIO[Unit] =
    Clock.nanoTime.flatMap(now => ZIO.succeed(startedAt.compareAndSet(NotBegun, now)).unit)

  /** How long a drain whose own bound is `bound` may take now. */
  def allowed(bound: Duration): UIO[Duration] =
    Clock.nanoTime.map: now =>
      val started = startedAt.get
      if started == NotBegun then bound
      else
        val left = Total.minus(Duration.fromNanos(math.max(0L, now - started)))
        if left.isNegative then Duration.Zero else bound.min(left)

  /** `effect`, abandoned after [[allowed]] of `bound`, and not started at all when nothing is left; `None` when it was not finished. */
  def within[R, E, A](bound: Duration)(effect: ZIO[R, E, A]): ZIO[R, E, Option[A]] =
    allowed(bound).flatMap(limit => if limit <= Duration.Zero then ZIO.none else effect.timeout(limit))

  /** For specs: forgets that the shutdown began. */
  private[http] def reset(): Unit = startedAt.set(NotBegun)

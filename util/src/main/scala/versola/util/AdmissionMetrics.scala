package versola.util

import zio.metrics.{Metric, MetricLabel}
import zio.metrics.Metric.{Gauge, Histogram}
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.{Chunk, Clock, UIO, ZIO}

/** What the Argon2id admission control (`Argon2Config.maxConcurrent`) is doing, as `/metrics`.
  *
  * Password hashing is one of the two CPU-bound paths of auth, and it runs behind a semaphore, so
  * CPU stops following demand once the permits are all held: the pod's CPU sits at what the
  * permits admit while logins queue. Utilisation of the CPU request is then the wrong thing to
  * scale on, and these are the right ones:
  *
  *  - `argon2_hashes_waiting` -- hashes queued for a permit; non-zero means demand exceeds what
  *    this pod admits
  *  - `argon2_hashes_in_flight` over `argon2_max_concurrent` -- how much of the admission this pod
  *    is using
  *  - `argon2_hash_wait_seconds` -- how long a hash waited for a permit; the part of a login's
  *    latency that more replicas would remove
  *  - `argon2_hash_duration_seconds` -- the hash itself, once admitted
  *
  * No labels: there is one semaphore per process. Registered in every service that builds a
  * [[SecurityService]] (central and edge never hash, and report zeros).
  */
object AdmissionMetrics:

  private val boundaries: Boundaries =
    Boundaries.fromChunk(Chunk(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0))

  /** The process-wide instance: no labels, because there is one semaphore per process. */
  val default: AdmissionMetrics = AdmissionMetrics(Set.empty)

/** One set of the metrics above. `labels` exist only so a test can have a set of its own: the
  * registry is process-wide, and two semaphores in one JVM (a suite's, the seeder's) would otherwise
  * write the same gauges.
  */
final class AdmissionMetrics(labels: Set[MetricLabel]):
  import AdmissionMetrics.boundaries

  val maxConcurrent: Gauge[Double] = Metric.gauge("argon2_max_concurrent").tagged(labels)
  val inFlight: Gauge[Double] = Metric.gauge("argon2_hashes_in_flight").tagged(labels)
  val waiting: Gauge[Double] = Metric.gauge("argon2_hashes_waiting").tagged(labels)
  private val waitSeconds = Metric.histogram("argon2_hash_wait_seconds", boundaries).tagged(labels)
  private val hashSeconds = Metric.histogram("argon2_hash_duration_seconds", boundaries).tagged(labels)

  /** Runs `hash` under `permit`, recording the wait for it, the time it was held, and the queue.
    *
    * `waiting` is decremented exactly once however the call ends: by the body when a permit was
    * granted, by the cleanup when the fiber was interrupted (or the semaphore failed) while it was
    * still queued. Without that, a client that disconnects mid-queue would leave the gauge
    * counting a hash that is no longer there, for the life of the process.
    */
  def admitted[R, E, A](permit: ZIO[R, E, A] => ZIO[R, E, A])(hash: ZIO[R, E, A]): ZIO[R, E, A] =
    for
      granted <- zio.Ref.make(false)
      queuedAt <- Clock.nanoTime
      result <- (waiting.increment *> permit(
        for
          // One uninterruptible step: interrupted between the flag and the decrement, the cleanup
          // below would see a granted permit and skip a decrement that never happened.
          _ <- ZIO.uninterruptible(granted.set(true) *> waiting.decrement)
          grantedAt <- Clock.nanoTime
          _ <- waitSeconds.update((grantedAt - queuedAt) / 1e9)
          // acquire/release, not increment-then-ensuring: an interrupt landing between the two would
          // leave the gauge counting a hash that is not running.
          result <- ZIO.acquireReleaseWith(inFlight.increment)(_ =>
            inFlight.decrement *> Clock.nanoTime.flatMap(done => hashSeconds.update((done - grantedAt) / 1e9)),
          )(_ => hash)
        yield result,
      )).ensuring(granted.get.flatMap(acquired => waiting.decrement.unless(acquired)))
    yield result

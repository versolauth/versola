package versola.loadgen.protocol

import zio.{UIO, ZIO}

import java.util.concurrent.atomic.AtomicInteger

/** How many HTTP calls this driver has in the air, which is §11's `loadgen_inflight_requests` and
  * one of the readings the campaign's verdict rests on (`DriverVitals.inflightRequests`).
  *
  * A saturated client is the way a driver stops measuring the SUT and starts measuring itself:
  * requests queue inside the emulator, the wait is charged to the SUT's latency, and nothing else
  * in the report distinguishes that from a slow SUT. Counting it is what makes the distinction
  * possible.
  *
  * Process-global, and an `AtomicInteger` rather than a `Ref`, for the same reason
  * [[versola.loadgen.metrics.LoadgenMetrics]]' counters are: every protocol client in the process
  * shares one connection pool, so the quantity is a property of the process, and the increment
  * sits on the hot path of every hop the campaign makes.
  */
object InflightRequests:

  private val outstanding = AtomicInteger(0)

  def current: UIO[Int] = ZIO.succeed(outstanding.get)

  /** Holds the count for the duration of `effect`, releasing it however the effect ends --
    * including interruption, which is what a drain, a rebalance or a shutdown does to the
    * sessions that own these calls.
    */
  private[protocol] def around[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(ZIO.succeed(outstanding.incrementAndGet()))(_ => ZIO.succeed(outstanding.decrementAndGet()))(
      _ => effect,
    )

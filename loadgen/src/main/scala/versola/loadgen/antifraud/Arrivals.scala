package versola.loadgen.antifraud

import zio.{Clock, Duration, Fiber, Ref, Semaphore, UIO, ZIO}

/** What an arrival process did: how many sessions it started, and how many had to wait for a slot.
  *
  * A wait means the process is the limit, not the system: `maxInFlight` sessions were already running. It is
  * reported so that a run whose latencies are of a loadgen that could not keep up is read as one.
  */
case class ArrivalSummary(arrivals: Long, waited: Long)

object Arrivals:

  /** Starts `session` at `rate` per second for `duration`, evenly spaced, each in a fiber of its own, and returns
    * when all of them have ended.
    *
    * `next` is called in the arrival loop, one call at a time, and makes what the session is given (a number,
    * a draw): the generators of numbers are not safe to share between fibers.
    */
  def run[A](rate: Double, duration: Duration, maxInFlight: Int)(next: UIO[A])(session: A => UIO[Unit]): UIO[ArrivalSummary] =
    val count = math.max(1L, math.round(rate * duration.toMillis / 1000.0))
    val spacingNanos = (1e9 / rate).toLong
    for
      slots <- Semaphore.make(maxInFlight.toLong)
      started <- Clock.nanoTime
      waited <- Ref.make(0L)
      fibers <- ZIO.foldLeft(0L until count)(List.empty[Fiber[Nothing, Unit]]): (fibers, index) =>
        for
          now <- Clock.nanoTime
          due = started + index * spacingNanos
          _ <- ZIO.sleep(Duration.fromNanos(due - now)).when(due > now)
          input <- next
          free <- slots.available
          _ <- waited.update(_ + 1).when(free == 0)
          fiber <- slots.withPermit(session(input)).fork
        yield fiber :: fibers
      _ <- Fiber.awaitAll(fibers)
      delayed <- waited.get
    yield ArrivalSummary(arrivals = count, waited = delayed)

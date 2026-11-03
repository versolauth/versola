package versola.loadgen.antifraud

import zio.{Semaphore, UIO, ZIO}

import java.util.concurrent.atomic.AtomicBoolean

/** Solves proofs of work on threads of their own, a bounded number at a time.
  *
  * The search is CPU and runs for as long as the difficulty makes it (a quarter of a second at 18 bits on one core), so
  * it is kept off the threads the scenarios' fibers run on, and no more of them run than there are cores to give them:
  * otherwise the solving would starve the requests it is part of and the latencies measured would be the loadgen's.
  *
  * @param slots proofs solved at the same time
  */
final class PowSolver(slots: Semaphore):

  /** A nonce, the hashes it took and the time inside the slot (the wait for one is not in it); `None` when the search
    * was stopped (the fiber was interrupted). */
  def solve(seed: String, difficulty: Int): UIO[Option[PowSolver.Solved]] =
    slots.withPermit:
      ZIO.suspendSucceed:
        val stop = AtomicBoolean(false)
        ZIO
          .attemptBlockingCancelable:
            val started = System.nanoTime()
            Pow.solve(seed, difficulty, () => stop.get()).map(found => PowSolver.Solved(found, (System.nanoTime() - started) / 1000L))
          (ZIO.succeed(stop.set(true)))
          .orDie

object PowSolver:
  case class Solved(solution: Pow.Solution, micros: Long)

  /** `threads` proofs at a time; 0 is one per processor. */
  def make(threads: Int): UIO[PowSolver] =
    Semaphore.make(if threads > 0 then threads.toLong else math.max(1, Runtime.getRuntime.availableProcessors()).toLong).map(PowSolver(_))

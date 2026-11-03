package versola.loadgen.antifraud

import org.HdrHistogram.ConcurrentHistogram
import zio.json.*

import java.util.concurrent.atomic.LongAdder

/** What the challenges of one scenario cost the client, so that an attack that pays with work reads as one and not as a
  * request that was refused or one that was never asked.
  *
  * @param received        proof-of-work challenges put to the client (a request may be put several)
  * @param solved          of them, the ones the client found a nonce for and sent back
  * @param unanswered      the ones it did not answer: a kind it cannot solve, more rounds than `challenge.max-rounds`, or a
  *                        difficulty over `challenge.max-difficulty` (what it is not willing to pay)
  * @param paused          requests of a plain client (no script) that were left pending, with no code sent
  * @param pauseHonoured   of them, the ones whose ask for the code after the pause was honoured
  * @param earlyAsks       asks made before the server's pause was over (answered with the same page; not charged)
  * @param solveP50Ms      the time the client spent finding a nonce, per challenge: its own CPU, not the server's
  * @param solveP99Ms      likewise, the 99th percentile
  * @param hashes          hashes computed in all
  * @param hashesP50       hashes per solved challenge, median
  * @param hashesP99       hashes per solved challenge, 99th percentile
  * @param requestTotal    a request for a code from its first submit to the page it ended on, **solving or waiting included**:
  *                        what the person at the other end waited for, where the `otp-request` latency is the server's alone
  */
case class ChallengeSnapshot(
    received: Long,
    solved: Long,
    unanswered: Long,
    paused: Long,
    pauseHonoured: Long,
    earlyAsks: Long,
    solveP50Ms: Double,
    solveP99Ms: Double,
    hashes: Long,
    hashesP50: Long,
    hashesP99: Long,
    requestTotal: Quantiles,
) derives JsonCodec

final class ChallengeStats:
  val received, solved, unanswered, paused, pauseHonoured, earlyAsks, hashes = LongAdder()
  private val solveMicros = ConcurrentHistogram(1L, Measurements.highestMicros, 3)
  private val hashCounts = ConcurrentHistogram(1L, 1L << 40, 3)
  private val totalMicros = ConcurrentHistogram(1L, Measurements.highestMicros, 3)
  private val totalErrors = LongAdder()

  def recordSolve(micros: Long, hashesTried: Long): Unit =
    solved.increment()
    hashes.add(hashesTried)
    solveMicros.recordValue(math.min(math.max(micros, 1L), Measurements.highestMicros))
    hashCounts.recordValue(math.min(math.max(hashesTried, 1L), 1L << 40))

  def recordTotal(micros: Long): Unit =
    totalMicros.recordValue(math.min(math.max(micros, 1L), Measurements.highestMicros))

  def recordTotalFailed(): Unit = totalErrors.increment()

  /** `None` when no challenge was put and nothing was left pending: the scenario's report has no section for it. */
  def snapshot: Option[ChallengeSnapshot] =
    Option.when(received.sum > 0 || paused.sum > 0):
      ChallengeSnapshot(
        received = received.sum,
        solved = solved.sum,
        unanswered = unanswered.sum,
        paused = paused.sum,
        pauseHonoured = pauseHonoured.sum,
        earlyAsks = earlyAsks.sum,
        solveP50Ms = solveMicros.getValueAtPercentile(50.0) / 1000.0,
        solveP99Ms = solveMicros.getValueAtPercentile(99.0) / 1000.0,
        hashes = hashes.sum,
        hashesP50 = hashCounts.getValueAtPercentile(50.0),
        hashesP99 = hashCounts.getValueAtPercentile(99.0),
        requestTotal = Quantiles(
          count = totalMicros.getTotalCount,
          errors = totalErrors.sum,
          p50Ms = totalMicros.getValueAtPercentile(50.0) / 1000.0,
          p99Ms = totalMicros.getValueAtPercentile(99.0) / 1000.0,
          maxMs = totalMicros.getMaxValue / 1000.0,
        ),
      )

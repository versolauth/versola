package versola.loadgen.antifraud

import zio.json.*

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/** How the requests of a scenario ended, as the client sees them.
  *
  * @param requested  codes asked for
  * @param sent       the conversation went on to the code step (a code was sent, or a stand-in was)
  * @param refused    the conversation ended in "access denied": the SMS budget or the submission limit
  * @param other      any other page after the number was submitted
  * @param verified   codes entered that were accepted
  * @param rejected   codes entered that were not
  * @param failed     calls that did not complete (transport, unexpected status, malformed page)
  * @param notEntered codes that were not entered, as a user who gave up (counted, not sent as a verify)
  * @param challenged requests that were challenged: put a proof of work (script client) or left pending (plain client).
  *                   Counted apart from `refused`: a challenged request that pays is not a refused one
  * @param solved     of the challenged, the ones that did the work and were sent the code
  * @param paidByWaiting of the challenged, the ones of a plain client that waited the pause, asked again and were sent it
  */
case class OtpOutcomes(
    requested: Long,
    sent: Long,
    refused: Long,
    other: Long,
    verified: Long,
    rejected: Long,
    failed: Long,
    notEntered: Long,
    challenged: Long = 0L,
    solved: Long = 0L,
    paidByWaiting: Long = 0L,
) derives JsonCodec

final class Tally:
  val requested, sent, refused, other, verified, rejected, failed, notEntered, challenged, solved, paidByWaiting = LongAdder()
  private val reasons = ConcurrentHashMap[String, LongAdder]()

  /** Adds what `that` counted: a scenario that keeps the requests of its groups apart reports them together too. */
  def absorb(that: Tally): Unit =
    requested.add(that.requested.sum)
    sent.add(that.sent.sum)
    refused.add(that.refused.sum)
    other.add(that.other.sum)
    verified.add(that.verified.sum)
    rejected.add(that.rejected.sum)
    failed.add(that.failed.sum)
    notEntered.add(that.notEntered.sum)
    challenged.add(that.challenged.sum)
    solved.add(that.solved.sum)
    paidByWaiting.add(that.paidByWaiting.sum)
    that.failures.foreach((reason, n) => reasons.computeIfAbsent(reason, _ => LongAdder()).add(n))

  def fail(reason: String): Unit =
    failed.increment()
    reasons.computeIfAbsent(reason, _ => LongAdder()).increment()

  /** Why calls failed, by a short description of the failure. */
  def failures: Map[String, Long] = reasons.asScala.view.mapValues(_.sum).toMap

  def snapshot: OtpOutcomes =
    OtpOutcomes(
      requested = requested.sum,
      sent = sent.sum,
      refused = refused.sum,
      other = other.sum,
      verified = verified.sum,
      rejected = rejected.sum,
      failed = failed.sum,
      notEntered = notEntered.sum,
      challenged = challenged.sum,
      solved = solved.sum,
      paidByWaiting = paidByWaiting.sum,
    )

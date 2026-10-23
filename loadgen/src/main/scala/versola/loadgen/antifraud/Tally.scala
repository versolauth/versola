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
) derives JsonCodec

final class Tally:
  val requested, sent, refused, other, verified, rejected, failed, notEntered = LongAdder()
  private val reasons = ConcurrentHashMap[String, LongAdder]()

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
    )

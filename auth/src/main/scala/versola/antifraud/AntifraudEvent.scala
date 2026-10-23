package versola.antifraud

import versola.oauth.client.model.{ClientId, TenantId}

import java.time.Instant
import java.util.UUID

/** What a step of a sign-in did, in the terms the antifraud rules and statistics need: which action, for which
  * destination, and how it ended. One row of the event log (`af_event`).
  *
  * The destination is never stored as itself: [[subjectHash]] is a keyed hash of the phone number or address,
  * which is enough to count and to correlate and not enough to read back, and [[destPrefix]] is the leading
  * digits of a phone number, the granularity at which a premium range is recognised.
  */
case class AntifraudEvent(
    id: UUID,
    at: Instant,
    tenantId: Option[TenantId],
    clientId: ClientId,
    /** The conversation the step belongs to, so a request can be matched with the verification that followed it. */
    conversationId: UUID,
    action: AntifraudAction,
    outcome: AntifraudOutcome,
    channel: AntifraudChannel,
    subjectHash: String,
    destPrefix: Option[String],
    userAgent: Option[String],
    /** The decision, score and rules that produced this outcome. Filled in once decisions exist (P0-1 of #771);
      * an event written before then carries none. */
    decision: Option[String] = None,
    score: Option[Int] = None,
    rules: List[String] = Nil,
)

enum AntifraudAction:
  case otp_request, otp_verify

/** How the step ended. Closed on purpose: it is a label of the metrics and a column the statistics group by. */
enum AntifraudOutcome:
  /** A code was handed to the provider. */
  case sent
  /** No code was sent for a destination that is not a known user, so nothing reveals which numbers are. */
  case suppressed
  /** The request was refused by a rate limit or a ban before anything was sent. */
  case throttled
  /** The provider could not be reached or refused the message. */
  case provider_error
  case verified
  case wrong_code
  /** The code was not even looked at: the destination is banned from submitting. */
  case locked
  /** The antifraud decision refused the request before anything was sent. */
  case blocked
  /** The antifraud decision was `Fake`: the answer was that of a send and nothing was sent. */
  case faked
  /** A code was entered after a fake send: nobody received one, so whoever entered it guessed or is a script. */
  case fake_verify

enum AntifraudChannel:
  case sms, email

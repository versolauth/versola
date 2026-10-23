package versola.oauth.client.model

import versola.user.model.UserId
import zio.json.{JsonCodec, jsonHint}

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Mirrors central's `PasskeyEnrollmentMode` -- see there for what each case means. */
enum PasskeyEnrollmentMode derives JsonCodec:
  @jsonHint("off")
  case off

  @jsonHint("offered")
  case offered

  @jsonHint("required_after_grace")
  case requiredAfterGrace

  @jsonHint("required")
  case required

/** What the user is shown after a sign-in that left them without a passkey. */
enum PasskeyOffer:
  /** Nothing: the sign-in finishes. */
  case None

  /** The enrolment card with a skip button. */
  case Skippable

  /** The enrolment card without one: the sign-in cannot finish until a passkey is registered. */
  case Mandatory

/** What `auth` remembers of one user's passkey offers: when the first was made (the grace in days runs from it) and how
  * many were skipped (the grace in skips counts them). Dropped once the user has a passkey.
  */
case class PasskeyEnrollmentState(
    userId: UserId,
    firstOfferedAt: Instant,
    skips: Int,
)

/** Mirrors central's `PasskeyPolicy` -- see there for what the fields mean. */
case class PasskeyPolicy(
    mode: PasskeyEnrollmentMode,
    graceSkips: Option[Int],
    graceDays: Option[Int],
) derives JsonCodec:

  /** What a user without a passkey is offered, given what is on record for them. A grace that cannot be told to be
    * running (no limit set, which central refuses to store) counts as used up: fail closed.
    */
  def offer(state: Option[PasskeyEnrollmentState], now: Instant): PasskeyOffer =
    mode match
      case PasskeyEnrollmentMode.off => PasskeyOffer.None
      case PasskeyEnrollmentMode.offered => PasskeyOffer.Skippable
      case PasskeyEnrollmentMode.required => PasskeyOffer.Mandatory
      case PasskeyEnrollmentMode.requiredAfterGrace =>
        val skipsLeft = graceSkips.exists(limit => state.fold(0)(_.skips) < limit)
        val daysLeft = graceDays.exists(limit => state.forall(s => now.isBefore(s.firstOfferedAt.plus(limit.toLong, ChronoUnit.DAYS))))
        // Each limit that is set must still be open: the grace ends with the first to run out.
        val open = (graceSkips.isEmpty || skipsLeft) && (graceDays.isEmpty || daysLeft) && (graceSkips.isDefined || graceDays.isDefined)
        if open then PasskeyOffer.Skippable else PasskeyOffer.Mandatory

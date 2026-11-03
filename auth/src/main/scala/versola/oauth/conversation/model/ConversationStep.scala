package versola.oauth.conversation.model

import versola.auth.model.{OtpCode, StepId}
import versola.oauth.client.model.{PrimaryCredential, ScopeToken}

import java.time.Instant

sealed trait ConversationStep(val id: StepId)

object ConversationStep:
  case class Credential(
      primaryCredentials: List[PrimaryCredential],
      inlinePassword: Boolean,
      passkey: Boolean,
      /** Whether the card offers a register button alongside sign-in. */
      registration: Boolean = false,
      passkeyRequest: Option[String] = None, // serialized assertion ceremony state, set by GET options
      passkeyFailed: Boolean = false,        // set when a submitted assertion fails verification
      loginFailed: Boolean = false,          // set when login+password submission fails authentication
      /** The card asks for nothing but a passkey of the user the conversation already identifies: a
        * step-up whose ACR needs `passkey`. The form starts the assertion by itself. */
      passkeyOnly: Boolean = false,
      /** Set when this passkey-only step was chosen from the code page in place of the code: the code step to go back
        * to, as it was left (what was sent, and when, is kept; nothing is sent again by going back). A passkey passed
        * here satisfies the factor the code was for, and the conversation goes on from where the code would have led. */
      codeStep: Option[Otp] = None,
  ) extends ConversationStep(StepId.Credential)

  case class Otp(
      real: Option[Otp.Real],
      timesRequested: Int,
      timesSubmitted: Int,
      factorIndex: Int,
      rateLimitExceeded: Boolean,
      lockedSeconds: Int,
      lastSentAt: Option[Instant],
      /** For a pending step: the moment from which the page's ask for the code is honoured (when it was left pending plus
        * `antifraud.challenge.pause`). The pause is what a request that answers no proof of work pays instead. A step
        * stored without it (before it existed) is honoured at once. */
      pendingUntil: Option[Instant] = None,
      /** How many times the person has gone from this code step to an alternative (see [[CodeAlternative]]). It bounds
        * the going back and forth for the life of the conversation, and is kept by the step a resend makes. */
      switched: Int = 0,
  ) extends ConversationStep(StepId.Otp):
    def isFake: Boolean = real.isEmpty

    /** The step a request for a code that has to prove itself is left on, before any code is sent (`timesRequested` is 1
      * from the first send on): the page asks for the code by itself, as a resend, and answers the challenge there. */
    def pending: Boolean = timesRequested == 0

    /** Whole seconds of the pause still to wait at `now`; 0 when it is over, and for a step that is not pending. */
    def pauseRemaining(now: Instant): Int =
      if !pending then 0
      else pendingUntil.fold(0)(until => math.max(0L, (until.toEpochMilli - now.toEpochMilli + 999) / 1000).toInt)

  object Otp:
    case class Real(code: OtpCode)

    /** Most times a conversation may move from its code step to an alternative. A switch writes the conversation and
      * sends nothing, so nothing but this bounds a page that only ever flips. It is a count of the conversation and not
      * a submission of the code limiter: that one bans the number from asking for codes, and a count of switches on
      * it would let anyone who starts a conversation for a number lock its owner out of the code without a single
      * code sent. */
    val MaxSwitches: Int = 5

  case class Password(
      timesSubmitted: Int,
      oldPasswordChangedAt: Option[Instant], // Set when user enters old password
      factorIndex: Int,
      rateLimitExceeded: Boolean,
      temporaryExpired: Boolean = false, // Set when the temporary password has expired
  ) extends ConversationStep(StepId.Password)

  /** Rendered after a user successfully authenticates with a temporary password.
    * The user must choose a new permanent password before proceeding.
    */
  case class SetPassword(
      factorIndex: Int,
      timesSubmitted: Int,
      rateLimitExceeded: Boolean,
      passwordReused: Boolean,
  ) extends ConversationStep(StepId.SetPassword)

  case class PasskeyEnroll(
      request: String,              // serialized registration ceremony state
      publicKeyOptions: String,     // JSON for navigator.credentials.create()
      enrollFailed: Boolean = false, // set when a submitted registration fails server-side verification
  ) extends ConversationStep(StepId.PasskeyEnroll)

  /** Asks the user to authorize the client for the requested scope. The scope is carried on the
    * step rather than read back off the conversation so a submission is validated against the
    * exact set that was displayed. Presentation data (localized client name, logo, scope
    * descriptions) is resolved at render time from configuration, as for every other step.
    */
  case class Consent(
      requestedScope: Set[ScopeToken],
      allowPartial: Boolean,
      /** Set when a submitted grant was rejected, e.g. it dropped `openid` or was partial for a
        * client that does not allow partial grants. */
      invalidGrant: Boolean = false,
  ) extends ConversationStep(StepId.Consent)

  case object AccessDenied extends ConversationStep(StepId.AccessDenied)

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
  ) extends ConversationStep(StepId.Credential)

  case class Otp(
      real: Option[Otp.Real],
      timesRequested: Int,
      timesSubmitted: Int,
      factorIndex: Int,
      rateLimitExceeded: Boolean,
      lockedSeconds: Int,
      lastSentAt: Option[Instant],
  ) extends ConversationStep(StepId.Otp):
    def isFake: Boolean = real.isEmpty

  object Otp:
    case class Real(code: OtpCode)

  /** An OTP request the antifraud decision wants a proof from the client for, which is asked before any code is sent.
    * What the OTP step needs to go on once the proof is given is kept here: where it was going (`factorIndex`) and
    * what decided it (`score`, `rules`); the credential is on the conversation.
    */
  case class Proof(
      kind: String,
      token: String,
      level: Int,
      params: Map[String, String],
      factorIndex: Int,
      score: Int,
      rules: List[String],
      /** Wrong solutions so far. */
      attempts: Int,
  ) extends ConversationStep(StepId.Proof)

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

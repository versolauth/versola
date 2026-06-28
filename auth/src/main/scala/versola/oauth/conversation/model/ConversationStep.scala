package versola.oauth.conversation.model

import versola.auth.model.{OtpCode, StepId}
import versola.oauth.client.model.PrimaryCredential

import java.time.Instant

sealed trait ConversationStep(val id: StepId)

object ConversationStep:
  case class Credential(
      primaryCredentials: List[PrimaryCredential],
      inlinePassword: Boolean,
      passkey: Boolean,
      passkeyRequest: Option[String] = None, // serialized assertion ceremony state, set by GET options
      passkeyFailed: Boolean = false, // set when a submitted assertion fails verification
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

  case class Password(
      timesSubmitted: Int,
      oldPasswordChangedAt: Option[Instant], // Set when user enters old password
      factorIndex: Int,
      rateLimitExceeded: Boolean,
  ) extends ConversationStep(StepId.Password)

  case class PasskeyEnroll(
      request: String, // serialized registration ceremony state
      publicKeyOptions: String, // JSON for navigator.credentials.create()
      enrollFailed: Boolean = false, // set when a submitted registration fails server-side verification
  ) extends ConversationStep(StepId.PasskeyEnroll)

  case object AccessDenied extends ConversationStep(StepId.AccessDenied)

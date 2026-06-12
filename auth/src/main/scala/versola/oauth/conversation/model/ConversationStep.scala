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
  ) extends ConversationStep(StepId.Credential)

  case class Otp(
      real: Option[Otp.Real],
      timesRequested: Int,
      timesSubmitted: Int,
      factorIndex: Int,
  ) extends ConversationStep(StepId.Otp):
    def isFake: Boolean = real.isEmpty

  object Otp:
    case class Real(code: OtpCode)

  case class Password(
      timesSubmitted: Int,
      oldPasswordChangedAt: Option[Instant], // Set when user enters old password
      factorIndex: Int,
  ) extends ConversationStep(StepId.Password)

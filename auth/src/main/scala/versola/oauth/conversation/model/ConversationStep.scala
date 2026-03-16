package versola.oauth.conversation.model

import versola.auth.model.{OtpCode, StepId}

import java.time.Instant

sealed trait ConversationStep(val id: StepId)

object ConversationStep:
  case class Empty(
      primaryCredential: PrimaryCredential,
      passkey: Boolean,
  ) extends ConversationStep(StepId.Empty)

  case class Otp(
      real: Option[Otp.Real],
      timesRequested: Int,
      timesSubmitted: Int,
  ) extends ConversationStep(StepId.Otp):
    def isFake: Boolean = real.isEmpty

  object Otp:
    case class Real(code: OtpCode)

  case class Password(
      timesSubmitted: Int,
      oldPasswordChangedAt: Option[Instant], // Set when user enters old password
  ) extends ConversationStep(StepId.Password)

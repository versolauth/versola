package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.model.ConversationStep
import versola.oauth.conversation.otp.model.SendOtpResult
import versola.user.model.UserId
import zio.{IO, UIO, ZIO}

trait OtpDecisionService:
  def checkRequest(
      previous: Option[ConversationStep.Otp],
      userId: Option[UserId],
      isRegistering: Boolean,
      forceFake: Boolean,
  ): UIO[SendOtpResult]

object OtpDecisionService:
  class Impl() extends OtpDecisionService:
    // TODO rules, bans, etc
    override def checkRequest(
        previous: Option[ConversationStep.Otp],
        userId: Option[UserId],
        isRegistering: Boolean,
        forceFake: Boolean,
    ): UIO[SendOtpResult] =
      ZIO.succeed:
        previous match
          // The antifraud decision was `Fake`: answered like a send, nothing is sent.
          case _ if forceFake =>
            SendOtpResult.Success(fake = true)

          case Some(previous) if previous.isFake =>
            SendOtpResult.Success(fake = true)

          // A brand-new registration has no user id yet -- that absence is expected, not a
          // sign of enumeration, so a real code is still sent.
          case _ if userId.isEmpty && !isRegistering =>
            SendOtpResult.Success(fake = true)

          case Some(_) | None =>
            SendOtpResult.Success(fake = false)

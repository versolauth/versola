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
  ): UIO[SendOtpResult]

object OtpDecisionService:
  class Impl() extends OtpDecisionService:
    private val SentOtpLimit = 2
    private val SubmitOtpLimit = 3

    // TODO rules, bans, etc
    override def checkRequest(
        previous: Option[ConversationStep.Otp],
        userId: Option[UserId],
    ): UIO[SendOtpResult] =
      ZIO.succeed:
        previous match
          case Some(previous) if previous.timesRequested >= SentOtpLimit =>
            SendOtpResult.LimitsExceeded

          case Some(previous) if previous.isFake =>
            SendOtpResult.Success(fake = true)

          case Some(_) | None =>
            SendOtpResult.Success(fake = false)

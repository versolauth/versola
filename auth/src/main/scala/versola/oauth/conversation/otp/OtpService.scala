package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.model.{AuthId, ConversationStep}
import versola.oauth.conversation.otp.model.{OtpTemplate, SendOtpResult, SubmitOtpResult}
import versola.user.model.UserId
import versola.util.{Email, Phone}
import zio.{Task, UIO, ZIO, ZLayer}

trait OtpService:
  def prepareOtp(
      previous: Option[ConversationStep.Otp],
      userId: Option[UserId],
  ): UIO[Option[ConversationStep.Otp]]

  def sendOtp(
      otp: ConversationStep.Otp,
      credential: Either[Email, Phone],
      authId: AuthId,
  ): Task[Unit]

  def checkOtp(otp: ConversationStep.Otp, code: OtpCode): UIO[SubmitOtpResult]

object OtpService:
  def live = ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      otpGenerationService: OtpGenerationService,
      otpDecisionService: OtpDecisionService,
      emailOtpProvider: EmailOtpProvider
  ) extends OtpService:

    override def prepareOtp(
        previous: Option[ConversationStep.Otp],
        userId: Option[UserId],
    ): UIO[Option[ConversationStep.Otp]] =
      otpDecisionService.checkRequest(previous, userId).flatMap:
        case SendOtpResult.Success(fake) =>
          otpGenerationService.generateOtpCode
            .unless(fake)
            .map: codeOpt =>
              ConversationStep.Otp(
                real = codeOpt.map(ConversationStep.Otp.Real(_)),
                timesRequested = previous.fold(0)(_.timesRequested),
                timesSubmitted = 0,
              )
            .asSome
        case SendOtpResult.LimitsExceeded =>
          ZIO.none

    override def sendOtp(
        otp: ConversationStep.Otp,
        credential: Either[Email, Phone],
        authId: AuthId,
    ): Task[Unit] =
      (otp.real, credential) match
        case (None, _) =>
          ZIO.unit

        case (Some(otp), Left(email)) =>
          emailOtpProvider.sendOtp(email, otp.code, OtpTemplate("{{code}}"))

        case (Some(code), Right(phone)) =>
          ZIO.unit

    override def checkOtp(otp: ConversationStep.Otp, code: OtpCode): UIO[SubmitOtpResult] =
      if otp.timesSubmitted > 3 then
        ZIO.succeed(SubmitOtpResult.LimitsExceeded)
      else
        otp.real match
          case Some(otp) if otp.code == code =>
            ZIO.succeed(SubmitOtpResult.Success)
          case _ =>
            ZIO.succeed(SubmitOtpResult.Failure)

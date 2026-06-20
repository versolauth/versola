package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.{AuthId, ConversationStep}
import versola.oauth.conversation.otp.model.{SendOtpResult, SubmitOtpResult}
import versola.user.model.UserId
import versola.util.{Email, EnvName, Phone}
import zio.{Task, UIO, ZIO, ZLayer}

trait OtpService:
  def prepareOtp(
      previous: Option[ConversationStep.Otp],
      userId: Option[UserId],
      clientId: ClientId,
  ): UIO[ConversationStep.Otp]

  def sendOtp(
      otp: ConversationStep.Otp,
      credential: Either[Email, Phone],
      authId: AuthId,
      clientId: ClientId,
      uiLocales: Option[List[String]],
  ): Task[Unit]

  def checkOtp(otp: ConversationStep.Otp, code: OtpCode): UIO[SubmitOtpResult]

object OtpService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  class Impl(
      otpGenerationService: OtpGenerationService,
      otpDecisionService: OtpDecisionService,
      emailOtpProvider: EmailOtpProvider,
      smsOtpProvider: SmsOtpProvider,
      configService: OAuthConfigurationService,
      envName: EnvName
  ) extends OtpService:

    override def prepareOtp(
        previous: Option[ConversationStep.Otp],
        userId: Option[UserId],
        clientId: ClientId,
    ): UIO[ConversationStep.Otp] =
      otpDecisionService.checkRequest(previous, userId).flatMap:
        case SendOtpResult.Success(fake) =>
          ZIO
            .unless(fake):
              configService.getOtpSettings(clientId).flatMap: settings =>
                otpGenerationService.generateOtpCode(settings.length)
            .map: codeOpt =>
              ConversationStep.Otp(
                real = codeOpt.map(ConversationStep.Otp.Real(_)),
                timesRequested = previous.fold(0)(_.timesRequested),
                timesSubmitted = 0,
                factorIndex = 0,
                rateLimitExceeded = false,
                lockedSeconds = 0,
                lastSentAt = None,
              )

    override def sendOtp(
        otp: ConversationStep.Otp,
        credential: Either[Email, Phone],
        authId: AuthId,
        clientId: ClientId,
        uiLocales: Option[List[String]],
    ): Task[Unit] =
      (otp.real, credential) match
        case (Some(otp), Left(email)) if envName.isProd =>
          configService.getClientTemplate(clientId, uiLocales).flatMap: template =>
            emailOtpProvider.sendOtp(email, otp.code, template)

        case (Some(otp), Right(phone)) if envName.isProd =>
          configService.getClientTemplate(clientId, uiLocales).flatMap: template =>
            smsOtpProvider.sendOtp(phone, otp.code, template)

        case _ =>
          ZIO.unit

    override def checkOtp(otp: ConversationStep.Otp, code: OtpCode): UIO[SubmitOtpResult] =
      otp.real match
        case None =>
          ZIO.succeed(SubmitOtpResult.Failure)
        case Some(real) if real.code == code =>
          ZIO.succeed(SubmitOtpResult.Success)
        case _ =>
          ZIO.succeed(SubmitOtpResult.Failure)

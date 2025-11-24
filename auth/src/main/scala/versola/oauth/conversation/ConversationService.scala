package versola.oauth.conversation

import versola.auth.model.{OtpCode, StepId}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.conversation.otp.model.SubmitOtpResult
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.SessionRecord
import versola.oauth.token.AuthorizationCodeRepository
import versola.security.{Secret, SecureRandom, SecurityService}
import versola.user.UserRepository
import versola.util.{AuthPropertyGenerator, Base64, CoreConfig, Email, Phone}
import zio.*

trait ConversationService:
  def find(authId: AuthId): Task[Option[ConversationRecord]]

  def prepareInitialOtp(
      authId: AuthId,
      conversation: ConversationRecord,
      credential: Either[Email, Phone],
  ): Task[ConversationResult.Render]

  def checkOtp(
      record: ConversationRecord,
      otp: ConversationStep.Otp,
      submittedCode: OtpCode,
      authId: AuthId,
  ): Task[ConversationResult]

  def finish(
      authId: AuthId,
      record: ConversationRecord,
  ): Task[ConversationResult.Complete]

object ConversationService:
  def live =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _))

  class Impl(
      otpService: OtpService,
      conversationRepository: ConversationRepository,
      userRepository: UserRepository,
      authorizationCodeRepository: AuthorizationCodeRepository,
      sessionRepository: SessionRepository,
      authPropertyGenerator: AuthPropertyGenerator,
      securityService: SecurityService,
      config: CoreConfig,
  ) extends ConversationService:
    export conversationRepository.find

    override def prepareInitialOtp(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
    ): Task[ConversationResult.Render] =
      for
        userIdOpt <- userRepository.findByCredential(credential).map(_.map(_.id))
        otpOpt <- otpService.prepareOtp(previous = None, userId = userIdOpt)
        result <- otpOpt match
          case None =>
            ZIO.succeed(ConversationResult.LimitsExceeded)

          case Some(otp) =>
            val updatedConversation = conversation.copy(
              userId = userIdOpt,
              credential = Some(credential),
              step = otp,
            )
            conversationRepository.overwrite(authId, updatedConversation)
              .zipRight(otpService.sendOtp(otp, credential, authId))
              .zipRight(conversationRepository.overwrite(authId, updatedConversation.copy(step = otp.copy(timesRequested = 1))))
              .as(ConversationResult.RenderStep(otp))
      yield result

    override def checkOtp(
        record: ConversationRecord,
        otp: ConversationStep.Otp,
        submittedCode: OtpCode,
        authId: AuthId,
    ): Task[ConversationResult] =
      otpService.checkOtp(otp, submittedCode)
        .flatMap:
          case SubmitOtpResult.LimitsExceeded =>
            conversationRepository.delete(authId)
              .as(ConversationResult.LimitsExceeded)

          case SubmitOtpResult.Failure => // TODO add errors
            val updatedOtp = otp.copy(timesSubmitted = otp.timesSubmitted + 1)
            conversationRepository.overwrite(authId, record.copy(step = updatedOtp))
              .as(ConversationResult.RenderStep(updatedOtp))

          case SubmitOtpResult.Success =>
            ZIO.succeed(ConversationResult.StepPassed(otp))

    override def finish(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Complete] =
      for
        code <- authPropertyGenerator.nextAuthorizationCode
        userId = conversation.userId.get // TODO handle illegal state
        sessionId <- authPropertyGenerator.nextSessionId
        record = AuthorizationCodeRecord(
          clientId = conversation.clientId,
          userId = userId,
          redirectUri = conversation.redirectUri,
          scope = conversation.scope,
          codeChallenge = conversation.codeChallenge,
          codeChallengeMethod = conversation.codeChallengeMethod,
        )
        session = SessionRecord(
          userId = userId,
        )
        codeMac <- securityService.macBlake3(Secret(code), config.security.authCodes.pepper)

        _ <- authorizationCodeRepository.create(codeMac, record, 1.minute)
        _ <- sessionRepository.create(sessionId, session, 1.day)
        _ <- conversationRepository.delete(authId)
      yield ConversationResult.Complete(code, sessionId)

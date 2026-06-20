package versola.oauth.conversation

import versola.auth.model.{OtpCode, Password}
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.CheckPassword
import versola.oauth.client.model.ScopeToken
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.conversation.otp.model.SubmitOtpResult
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.SessionRecord
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.{AuthPropertyGenerator, Base64, CoreConfig, Email, Phone, Secret, SecureRandom, SecurityService}
import zio.*
import zio.json.ast.Json

trait ConversationService:
  def find(authId: AuthId): Task[Option[ConversationRecord]]

  def prepareInitialOtp(
      authId: AuthId,
      conversation: ConversationRecord,
      credential: Either[Email, Phone],
      factorIndex: Int,
  ): Task[ConversationResult.Render]

  def prepareInitialPassword(
      authId: AuthId,
      conversation: ConversationRecord,
      credential: Either[Email, Phone],
      factorIndex: Int,
  ): Task[ConversationResult.Render]

  def checkOtp(
      record: ConversationRecord,
      otp: ConversationStep.Otp,
      submittedCode: OtpCode,
      authId: AuthId,
  ): Task[ConversationResult]

  def preparePasswordStep(
      authId: AuthId,
      conversation: ConversationRecord,
      factorIndex: Int,
  ): Task[ConversationResult.Render]

  def checkPassword(
      record: ConversationRecord,
      passwordStep: ConversationStep.Password,
      submittedPassword: Password,
      authId: AuthId,
  ): Task[ConversationResult]

  def finish(
      authId: AuthId,
      record: ConversationRecord,
  ): Task[ConversationResult.Complete]

object ConversationService:
  def live =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _))

  class Impl(
      otpService: OtpService,
      passwordService: PasswordService,
      conversationRepository: ConversationRepository,
      userRepository: UserRepository,
      authorizationCodeRepository: AuthorizationCodeRepository,
      sessionRepository: SessionRepository,
      authPropertyGenerator: AuthPropertyGenerator,
      securityService: SecurityService,
      userInfoService: UserInfoService,
      config: CoreConfig,
  ) extends ConversationService:
    export conversationRepository.find

    override def prepareInitialOtp(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
        factorIndex: Int,
    ): Task[ConversationResult.Render] =
      for
        userOpt <- userRepository.findByCredential(credential)
        otpOpt <- otpService.prepareOtp(previous = None, userId = userOpt.map(_.id))
        result <- otpOpt match
          case None =>
            ZIO.succeed(ConversationResult.LimitsExceeded)

          case Some(otp) =>
            val otpWithFactor = otp.copy(factorIndex = factorIndex)
            val updatedConversation = conversation.copy(
              userId = userOpt.map(_.id),
              credential = Some(credential),
              step = otpWithFactor,
              userEmail = userOpt.flatMap(_.email),
              userPhone = userOpt.flatMap(_.phone),
              userLogin = userOpt.flatMap(_.login),
              userClaims = userOpt.map(_.claims),
            )
            conversationRepository.overwrite(authId, updatedConversation)
              .zipRight(otpService.sendOtp(otpWithFactor, credential, authId, conversation.clientId, conversation.uiLocales))
              .zipRight(conversationRepository.overwrite(authId, updatedConversation.copy(step = otpWithFactor.copy(timesRequested = 1))))
              .as(ConversationResult.RenderStep(otpWithFactor))
      yield result

    override def prepareInitialPassword(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
        factorIndex: Int,
    ): Task[ConversationResult.Render] =
      for
        userOpt <- userRepository.findByCredential(credential)
        passwordStep = ConversationStep.Password(
          timesSubmitted = 0,
          oldPasswordChangedAt = None,
          factorIndex = factorIndex,
        )
        updatedConversation = conversation.copy(
          userId = userOpt.map(_.id),
          credential = Some(credential),
          step = passwordStep,
          userEmail = userOpt.flatMap(_.email),
          userPhone = userOpt.flatMap(_.phone),
          userLogin = userOpt.flatMap(_.login),
          userClaims = userOpt.map(_.claims),
        )
        _ <- conversationRepository.overwrite(authId, updatedConversation)
      yield ConversationResult.RenderStep(passwordStep)

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

    override def preparePasswordStep(
        authId: AuthId,
        conversation: ConversationRecord,
        factorIndex: Int,
    ): Task[ConversationResult.Render] =
      val passwordStep = ConversationStep.Password(
        timesSubmitted = 0,
        oldPasswordChangedAt = None,
        factorIndex = factorIndex,
      )
      conversationRepository.overwrite(authId, conversation.copy(step = passwordStep))
        .as(ConversationResult.RenderStep(passwordStep))

    override def checkPassword(
        record: ConversationRecord,
        passwordStep: ConversationStep.Password,
        submittedPassword: Password,
        authId: AuthId,
    ): Task[ConversationResult] =
      record.userId match
        case None =>
          ZIO.succeed(ConversationResult.IllegalState)

        case Some(userId) =>
          if passwordStep.timesSubmitted >= 3 then
            conversationRepository.delete(authId)
              .as(ConversationResult.LimitsExceeded)
          else
            passwordService.verifyPassword(userId, submittedPassword).flatMap:
              case CheckPassword.Success =>
                ZIO.succeed(ConversationResult.StepPassed(passwordStep))

              case CheckPassword.OldPassword(changedAt) =>
                val updatedStep = passwordStep.copy(
                  timesSubmitted = passwordStep.timesSubmitted + 1,
                  oldPasswordChangedAt = Some(changedAt),
                )
                conversationRepository.overwrite(authId, record.copy(step = updatedStep))
                  .as(ConversationResult.RenderStep(updatedStep))

              case CheckPassword.Failure =>
                val updatedStep = passwordStep.copy(
                  timesSubmitted = passwordStep.timesSubmitted + 1,
                )
                conversationRepository.overwrite(authId, record.copy(step = updatedStep))
                  .as(ConversationResult.RenderStep(updatedStep))

    override def finish(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Complete] =
      for
        code <- authPropertyGenerator.nextAuthorizationCode
        userId = conversation.userId.get // TODO handle illegal state
        sessionId <- authPropertyGenerator.nextSessionId
        sessionIdMac <- securityService.mac(Secret(sessionId), config.security.sessions.pepper)
        accessToken <- authPropertyGenerator.nextAccessToken
        now <- Clock.instant
        record = AuthorizationCodeRecord(
          sessionId = sessionIdMac,
          clientId = conversation.clientId,
          userId = userId,
          redirectUri = conversation.redirectUri,
          scope = conversation.scope,
          codeChallenge = conversation.codeChallenge,
          codeChallengeMethod = conversation.codeChallengeMethod,
          requestedClaims = conversation.requestedClaims,
          uiLocales = conversation.uiLocales,
          nonce = conversation.nonce,
          accessToken = accessToken,
        )
        session = SessionRecord(
          userId = userId,
          clientId = conversation.clientId,
          userAgent = conversation.userAgent,
          createdAt = now,
        )
        codeMac <- securityService.mac(Secret(code), config.security.authCodes.pepper)

        _ <- authorizationCodeRepository.create(codeMac, record, 1.minute)
        _ <- sessionRepository.create(sessionIdMac, session, 1.day)
        _ <- conversationRepository.delete(authId)

        idTokenData <- if conversation.responseType.contains(ResponseTypeEntry.IdToken) && conversation.scope.contains(ScopeToken.OpenId) then
          generateIdTokenData(userId, conversation)
        else
          ZIO.none
      yield ConversationResult.Complete(
        redirectUri = conversation.redirectUri,
        state = conversation.state,
        code = code,
        sessionId = sessionIdMac,
        idTokenData = idTokenData,
      )

    private def generateIdTokenData(userId: UserId, conversation: ConversationRecord): Task[Option[ConversationResult.IdTokenData]] =
      for
        user = UserRecord(
          id = userId,
          email = conversation.userEmail,
          phone = conversation.userPhone,
          login = conversation.userLogin,
          claims = conversation.userClaims.getOrElse(Json.Obj()),
          uiLocales = conversation.uiLocales,
        )
        userInfo <- userInfoService.getUserInfoForIdToken(
          user = user,
          scope = conversation.scope,
          requestedClaims = conversation.requestedClaims,
          uiLocales = conversation.uiLocales,
          nonce = conversation.nonce,
        )
      yield Some(
        ConversationResult.IdTokenData(
          userId = user.id,
          claims = userInfo.claims,
          clientId = conversation.clientId,
        ),
      )

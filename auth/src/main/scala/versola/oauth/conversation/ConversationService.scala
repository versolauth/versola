package versola.oauth.conversation

import versola.auth.model.{OtpCode, Password}
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.CheckPassword
import versola.oauth.client.model.ScopeToken
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
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
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _))

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
      submissionLimiter: SubmissionLimiter,
  ) extends ConversationService:
    export conversationRepository.find

    private def accessDenied(authId: AuthId, record: ConversationRecord): Task[ConversationResult.Render] =
      conversationRepository.overwrite(authId, record.copy(step = ConversationStep.AccessDenied))
        .as(ConversationResult.RenderStep(ConversationStep.AccessDenied))

    private def renderStep(authId: AuthId, record: ConversationRecord, step: ConversationStep): Task[ConversationResult.Render] =
      conversationRepository.overwrite(authId, record.copy(step = step))
        .as(ConversationResult.RenderStep(step))

    /** Records a failed attempt, then renders the step the failure produced — denying access on a
      * persistent ban, flagging the rate limit (with the seconds until input reopens) on a
      * short-window hit, or rendering normally otherwise.
      */
    private def worstStatus(a: LimitStatus, b: LimitStatus): LimitStatus =
      (a, b) match
        case (LimitStatus.Banned, _) | (_, LimitStatus.Banned) => LimitStatus.Banned
        case (LimitStatus.RateLimited(s), _) => LimitStatus.RateLimited(s)
        case (_, LimitStatus.RateLimited(s)) => LimitStatus.RateLimited(s)
        case _ => LimitStatus.Allowed

    private def recordLimitAndRender(
        authId: AuthId,
        record: ConversationRecord,
        subject: String,
        challengeType: ChallengeType,
    )(step: Option[Long] => ConversationStep): Task[ConversationResult.Render] =
      submissionLimiter.recordLimit(record.clientId, subject, challengeType).flatMap:
        case LimitStatus.Banned => accessDenied(authId, record)
        case LimitStatus.RateLimited(retryAfter) => renderStep(authId, record, step(Some(retryAfter)))
        case LimitStatus.Allowed => renderStep(authId, record, step(None))

    override def prepareInitialOtp(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
        factorIndex: Int,
    ): Task[ConversationResult.Render] =
      val subject = credential.merge
      for
        status <- submissionLimiter.statusFor(
          conversation.clientId,
          subject,
          List(ChallengeType.OtpRequest, ChallengeType.OtpSubmit),
        )
        result <- status match
          case LimitStatus.Banned | LimitStatus.RateLimited(_) =>
            accessDenied(authId, conversation)
          case LimitStatus.Allowed =>
            for
              userOpt <- userRepository.findByCredential(credential)
              otp <- otpService.prepareOtp(previous = None, userId = userOpt.map(_.id), clientId = conversation.clientId)
              now <- Clock.instant
              sentStep = otp.copy(factorIndex = factorIndex, timesRequested = 1, lastSentAt = Some(now))
              updatedConversation = conversation.copy(
                userId = userOpt.map(_.id),
                credential = Some(credential),
                step = sentStep,
                userEmail = userOpt.flatMap(_.email),
                userPhone = userOpt.flatMap(_.phone),
                userLogin = userOpt.flatMap(_.login),
                userClaims = userOpt.map(_.claims),
              )
              _ <- conversationRepository.overwrite(authId, updatedConversation)
              _ <- otpService.sendOtp(sentStep, credential, authId, conversation.clientId, conversation.uiLocales)
              _ <- submissionLimiter.recordLimit(conversation.clientId, subject, ChallengeType.OtpRequest)
            yield ConversationResult.RenderStep(sentStep)
      yield result

    override def prepareInitialPassword(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
        factorIndex: Int,
    ): Task[ConversationResult.Render] =
      for
        userOpt <- userRepository.findByCredential(credential)
        status <- userOpt.fold[Task[LimitStatus]](ZIO.succeed(LimitStatus.Allowed)): user =>
          for
            userStatus <- submissionLimiter.isBanned(conversation.clientId, user.id.toString, ChallengeType.PasswordSubmit)
            credStatus <- submissionLimiter.isBanned(conversation.clientId, credential.merge, ChallengeType.PasswordSubmit)
          yield worstStatus(userStatus, credStatus)

        result <- status match
          case LimitStatus.Banned | LimitStatus.RateLimited(_) =>
            accessDenied(authId, conversation)
          case LimitStatus.Allowed =>
            val passwordStep = ConversationStep.Password(
              timesSubmitted = 0,
              oldPasswordChangedAt = None,
              factorIndex = factorIndex,
              rateLimitExceeded = false,
            )
            val updatedConversation = conversation.copy(
              userId = userOpt.map(_.id),
              credential = Some(credential),
              step = passwordStep,
              userEmail = userOpt.flatMap(_.email),
              userPhone = userOpt.flatMap(_.phone),
              userLogin = userOpt.flatMap(_.login),
              userClaims = userOpt.map(_.claims),
            )
            conversationRepository.overwrite(authId, updatedConversation)
              .as(ConversationResult.RenderStep(passwordStep))
      yield result

    override def checkOtp(
        record: ConversationRecord,
        otp: ConversationStep.Otp,
        submittedCode: OtpCode,
        authId: AuthId,
    ): Task[ConversationResult] =
      val subject = record.credential.map(_.merge).getOrElse("")
      submissionLimiter.isBanned(record.clientId, subject, ChallengeType.OtpSubmit).flatMap:
        case LimitStatus.Banned =>
          accessDenied(authId, record)
        case LimitStatus.RateLimited(retryAfter) =>
          renderStep(authId, record, otp.copy(rateLimitExceeded = true, lockedSeconds = retryAfter.toInt))
        case LimitStatus.Allowed =>
          otpService.checkOtp(otp, submittedCode).flatMap:
            case SubmitOtpResult.Failure =>
              recordLimitAndRender(authId, record, subject, ChallengeType.OtpSubmit): retryAfter =>
                otp.copy(
                  timesSubmitted = otp.timesSubmitted + 1,
                  rateLimitExceeded = retryAfter.isDefined,
                  lockedSeconds = retryAfter.fold(0)(_.toInt),
                )

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
        rateLimitExceeded = false,
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
          val userSubject = userId.toString
          val credSubjectOpt = record.credential.map(_.merge)
          val checkCredBan = credSubjectOpt.fold(ZIO.succeed(LimitStatus.Allowed))(s =>
            submissionLimiter.isBanned(record.clientId, s, ChallengeType.PasswordSubmit),
          )
          val recordCredFailure = credSubjectOpt.fold(ZIO.unit)(s =>
            submissionLimiter.recordLimit(record.clientId, s, ChallengeType.PasswordSubmit).unit,
          )
          for
            userBan <- submissionLimiter.isBanned(record.clientId, userSubject, ChallengeType.PasswordSubmit)
            credBan <- checkCredBan
            result <- worstStatus(userBan, credBan) match
              case LimitStatus.Banned =>
                accessDenied(authId, record)
              case LimitStatus.RateLimited(_) =>
                renderStep(authId, record, passwordStep.copy(rateLimitExceeded = true))
              case LimitStatus.Allowed =>
                passwordService.verifyPassword(userId, submittedPassword).flatMap:
                  case CheckPassword.Success =>
                    ZIO.succeed(ConversationResult.StepPassed(passwordStep))

                  case CheckPassword.OldPassword(changedAt) =>
                    recordCredFailure *>
                      recordLimitAndRender(authId, record, userSubject, ChallengeType.PasswordSubmit): retryAfter =>
                        passwordStep.copy(
                          timesSubmitted = passwordStep.timesSubmitted + 1,
                          oldPasswordChangedAt = Some(changedAt),
                          rateLimitExceeded = retryAfter.isDefined,
                        )

                  case CheckPassword.Failure =>
                    recordCredFailure *>
                      recordLimitAndRender(authId, record, userSubject, ChallengeType.PasswordSubmit): retryAfter =>
                        passwordStep.copy(
                          timesSubmitted = passwordStep.timesSubmitted + 1,
                          rateLimitExceeded = retryAfter.isDefined,
                        )
          yield result

    override def finish(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Complete] =
      for
        code <- authPropertyGenerator.nextAuthorizationCode
        userId = conversation.userId.get // TODO handle illegal state
        sessionId <- authPropertyGenerator.nextSessionId
        sessionIdMac <- securityService.mac(Secret(sessionId), config.security.sessions.pepper)
        accessToken <- authPropertyGenerator.nextAccessToken
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

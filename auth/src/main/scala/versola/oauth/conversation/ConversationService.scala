package versola.oauth.conversation

import versola.auth.model.CredentialDeviceType
import versola.auth.model.{OtpCode, Password}
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.challenge.passkey.{PasskeyRepository, WebAuthnError, WebAuthnService}
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.CheckPassword
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthMethodRef, PassedAuthFactor, PassedFactorRecord, PasskeySettings, ScopeToken}
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.conversation.otp.model.SubmitOtpResult
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{SessionRecord, UserAgentInfo}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.user.model.{Login, UserId, UserRecord}
import versola.util.{AuthPropertyGenerator, Base64, CoreConfig, Email, Phone, Secret, SecureRandom, SecurityService}
import zio.*
import zio.json.ast.Json

import java.time.Instant

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
      conversation: ConversationRecord,
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
      conversation: ConversationRecord,
      passwordStep: ConversationStep.Password,
      submittedPassword: Password,
      authId: AuthId,
  ): Task[ConversationResult]

  /** Authenticate a login+password submission from the combined credential form.
    * Looks up the user by login, verifies the password, and advances the conversation.
    * On failure re-renders the Credential step with `loginFailed = true`.
    */
  def checkLoginPassword(
      authId: AuthId,
      conversation: ConversationRecord,
      login: Login,
      password: Password,
  ): Task[ConversationResult]

  def finish(
      authId: AuthId,
      conversation: ConversationRecord,
  ): Task[ConversationResult.Render]

  /** Begin a discoverable assertion ceremony; stores the request state on the Credential step.
    * Returns the JSON public-key options to pass to `navigator.credentials.get()`.
    */
  def startPasskeyAssertion(
      authId: AuthId,
      conversation: ConversationRecord,
      step: ConversationStep.Credential,
      settings: PasskeySettings,
  ): Task[String]

  /** Verify the authenticator's assertion response.
    * On success: updates the conversation with the resolved user and advances to enrollment-offer or finish.
    * On failure: clears the passkeyRequest and re-renders the Credential step.
    *
    * `ipAddress` is a server-controlled throttle subject (the client cannot influence it), used
    * alongside the client-chosen credential id so random-id probing is still rate-limited.
    */
  def finishPasskeyAssertion(
      authId: AuthId,
      record: ConversationRecord,
      response: String,
      ipAddress: Option[String],
  ): Task[ConversationResult.Render]

  /** After all primary auth factors pass: if the tenant has passkey settings and the user has no
    * passkey yet, starts a registration ceremony and renders the PasskeyEnroll step.
    * Otherwise, finishes immediately.
    */
  def offerPasskeyEnroll(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Render]

  /** Finish a passkey enrollment ceremony and complete the conversation. */
  def finishPasskeyEnroll(
      authId: AuthId,
      conversation: ConversationRecord,
      enrollStep: ConversationStep.PasskeyEnroll,
      response: String,
      name: Option[String],
  ): Task[ConversationResult.Render]

  /** Skip passkey enrollment and complete the conversation. */
  def skipPasskey(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Render]

object ConversationService:
  def live =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _, _))

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
      webAuthnService: WebAuthnService,
      passkeyRepository: PasskeyRepository,
      configService: OAuthConfigurationService,
  ) extends ConversationService:
    export conversationRepository.find

    private def accessDenied(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Render] =
      conversationRepository.overwrite(authId, conversation.copy(step = ConversationStep.AccessDenied))
        .as(ConversationResult.RenderStep(ConversationStep.AccessDenied))

    private def renderStep(authId: AuthId, conversation: ConversationRecord, step: ConversationStep): Task[ConversationResult.Render] =
      conversationRepository.overwrite(authId, conversation.copy(step = step))
        .map:
          case true => ConversationResult.RenderStep(step)
          case false => ConversationResult.IllegalState

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
        conversation: ConversationRecord,
        subject: String,
        challengeType: ChallengeType,
    )(step: Option[Long] => ConversationStep): Task[ConversationResult.Render] =
      submissionLimiter.recordLimit(conversation.clientId, subject, challengeType).flatMap:
        case LimitStatus.Banned => accessDenied(authId, conversation)
        case LimitStatus.RateLimited(retryAfter) => renderStep(authId, conversation, step(Some(retryAfter)))
        case LimitStatus.Allowed => renderStep(authId, conversation, step(None))

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
              written <- conversationRepository.overwrite(authId, updatedConversation)
              result2 <- if written then
                for
                  _ <- otpService.sendOtp(sentStep, credential, authId, conversation.clientId, conversation.uiLocales)
                  _ <- submissionLimiter.recordLimit(conversation.clientId, subject, ChallengeType.OtpRequest)
                yield ConversationResult.RenderStep(sentStep): ConversationResult.Render
              else
                ZIO.succeed(ConversationResult.IllegalState)
            yield result2
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
          submissionLimiter.statusForSubjects(
            conversation.clientId,
            List(user.id.toString, credential.merge),
            ChallengeType.PasswordSubmit,
          )

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
              .map:
                case true => ConversationResult.RenderStep(passwordStep)
                case false => ConversationResult.IllegalState
      yield result

    override def checkOtp(
        conversation: ConversationRecord,
        otp: ConversationStep.Otp,
        submittedCode: OtpCode,
        authId: AuthId,
    ): Task[ConversationResult] =
      val subject = conversation.credential.map(_.merge).getOrElse("")
      submissionLimiter.isBanned(conversation.clientId, subject, ChallengeType.OtpSubmit).flatMap:
        case LimitStatus.Banned =>
          accessDenied(authId, conversation)
        case LimitStatus.RateLimited(retryAfter) =>
          renderStep(authId, conversation, otp.copy(rateLimitExceeded = true, lockedSeconds = retryAfter.toInt))
        case LimitStatus.Allowed =>
          otpService.checkOtp(otp, submittedCode).flatMap:
            case SubmitOtpResult.Failure =>
              recordLimitAndRender(authId, conversation, subject, ChallengeType.OtpSubmit): retryAfter =>
                otp.copy(
                  timesSubmitted = otp.timesSubmitted + 1,
                  rateLimitExceeded = retryAfter.isDefined,
                  lockedSeconds = retryAfter.fold(0)(_.toInt),
                )

            case SubmitOtpResult.Success =>
              Clock.instant.flatMap: now =>
                val otpMethods = Set(AuthMethodRef.otp) ++ conversation.credential.collect { case Right(_) => AuthMethodRef.sms }
                val updated = conversation.copy(amr = conversation.amr + (PassedAuthFactor.otp -> PassedFactorRecord(now, otpMethods)))
                conversationRepository.overwrite(authId, updated)
                  .map:
                    case true => ConversationResult.StepPassed(updated.copy(version = updated.version + 1))
                    case false => ConversationResult.IllegalState

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
        .map:
          case true => ConversationResult.RenderStep(passwordStep)
          case false => ConversationResult.IllegalState

    override def checkPassword(
        conversation: ConversationRecord,
        passwordStep: ConversationStep.Password,
        submittedPassword: Password,
        authId: AuthId,
    ): Task[ConversationResult] =
      conversation.userId match
        case None =>
          ZIO.succeed(ConversationResult.IllegalState)

        case Some(userId) =>
          val userSubject = userId.toString
          val credSubjectOpt = conversation.credential.map(_.merge)
          val subjects = credSubjectOpt.fold(List(userSubject))(s => List(userSubject, s))
          for
            ban    <- submissionLimiter.statusForSubjects(conversation.clientId, subjects, ChallengeType.PasswordSubmit)
            result <- ban match
              case LimitStatus.Banned =>
                accessDenied(authId, conversation)
              case LimitStatus.RateLimited(_) =>
                renderStep(authId, conversation, passwordStep.copy(rateLimitExceeded = true))
              case LimitStatus.Allowed =>
                passwordService.verifyPassword(userId, submittedPassword).flatMap:
                  case CheckPassword.Success =>
                    Clock.instant.flatMap: now =>
                      val updated =
                        conversation.copy(amr = conversation.amr + (PassedAuthFactor.password -> PassedFactorRecord(now, Set(AuthMethodRef.pwd))))
                      conversationRepository.overwrite(authId, updated)
                        .map:
                          case true => ConversationResult.StepPassed(updated.copy(version = updated.version + 1))
                          case false => ConversationResult.IllegalState

                  case CheckPassword.OldPassword(changedAt) =>
                    submissionLimiter.recordLimitAll(conversation.clientId, subjects, ChallengeType.PasswordSubmit).flatMap:
                      case LimitStatus.Banned => accessDenied(authId, conversation)
                      case LimitStatus.RateLimited(_) =>
                        renderStep(authId, conversation, passwordStep.copy(
                          timesSubmitted = passwordStep.timesSubmitted + 1,
                          oldPasswordChangedAt = Some(changedAt),
                          rateLimitExceeded = true,
                        ))
                      case LimitStatus.Allowed =>
                        renderStep(authId, conversation, passwordStep.copy(
                          timesSubmitted = passwordStep.timesSubmitted + 1,
                          oldPasswordChangedAt = Some(changedAt),
                          rateLimitExceeded = false,
                        ))

                  case CheckPassword.Failure =>
                    submissionLimiter.recordLimitAll(conversation.clientId, subjects, ChallengeType.PasswordSubmit).flatMap:
                      case LimitStatus.Banned => accessDenied(authId, conversation)
                      case LimitStatus.RateLimited(_) =>
                        renderStep(authId, conversation, passwordStep.copy(
                          timesSubmitted = passwordStep.timesSubmitted + 1,
                          rateLimitExceeded = true,
                        ))
                      case LimitStatus.Allowed =>
                        renderStep(authId, conversation, passwordStep.copy(
                          timesSubmitted = passwordStep.timesSubmitted + 1,
                          rateLimitExceeded = false,
                        ))
          yield result

    override def checkLoginPassword(
        authId: AuthId,
        conversation: ConversationRecord,
        login: Login,
        password: Password,
    ): Task[ConversationResult] =
      conversation.step match
        case cred: ConversationStep.Credential =>
          userRepository.findByLogin(login).flatMap:
            case None =>
              recordLimitAndRender(authId, conversation, login, ChallengeType.PasswordSubmit): _ =>
                cred.copy(loginFailed = true)
            case Some(user) =>
              val userSubject = user.id.toString
              submissionLimiter.statusForSubjects(conversation.clientId, List(login, userSubject), ChallengeType.PasswordSubmit).flatMap:
                case LimitStatus.Banned =>
                  accessDenied(authId, conversation)
                case LimitStatus.RateLimited(_) =>
                  renderStep(authId, conversation, cred.copy(loginFailed = true))
                case LimitStatus.Allowed =>
                  passwordService.verifyPassword(user.id, password).flatMap:
                    case CheckPassword.Success =>
                      Clock.instant.flatMap: now =>
                        val updated = conversation.copy(
                          userId = Some(user.id),
                          userLogin = user.login,
                          userClaims = Some(user.claims),
                          amr = conversation.amr + (PassedAuthFactor.password -> PassedFactorRecord(now, Set(AuthMethodRef.pwd))),
                        )
                        conversationRepository.overwrite(authId, updated).map:
                          case true => ConversationResult.StepPassed(updated.copy(version = updated.version + 1))
                          case false => ConversationResult.IllegalState
                    case _ =>
                      submissionLimiter.recordLimitAll(conversation.clientId, List(login, userSubject), ChallengeType.PasswordSubmit).flatMap:
                        case LimitStatus.Banned => accessDenied(authId, conversation)
                        case _ => renderStep(authId, conversation, cred.copy(loginFailed = true))
        case _ =>
          ZIO.succeed(ConversationResult.IllegalState)

    override def finish(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Render] =
      conversation.expectedUserId match
        case Some(expected) if !conversation.userId.contains(expected) =>
          accessDenied(authId, conversation)
        case _ =>
          for
            code <- authPropertyGenerator.nextAuthorizationCode
            userId = conversation.userId.get // TODO handle illegal state
            sessionId <- authPropertyGenerator.nextSessionId
            sessionIdMac <- securityService.mac(Secret(sessionId), config.security.sessionsSecret)
            accessToken <- authPropertyGenerator.nextAccessToken
            now <- Clock.instant
            amr = AuthMethodRef.amrClaim(conversation.amr)
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
              amr = amr,
              authTime = now,
            )
            session = SessionRecord(
              userId = userId,
              clientId = conversation.clientId,
              userAgent = UserAgentInfo.parse(conversation.userAgent),
              createdAt = now,
              amr = conversation.amr,
            )
            codeMac <- securityService.mac(Secret(code), config.security.authCodesSecret)
            sessionTtl <- configService.getSessionTtl(conversation.clientId)
            sessionIdleTtl <- if conversation.scope.contains(ScopeToken.OfflineAccess) then ZIO.none
              else configService.getSessionIdleTtl(conversation.clientId)
            claimed <- conversationRepository.delete(authId, conversation.version)
            result <- if claimed then
              for
                _ <- authorizationCodeRepository.create(codeMac, record, 1.minute)
                _ <- sessionRepository.create(sessionIdMac, session, sessionTtl, sessionIdleTtl)
                idTokenData <- if conversation.responseType.contains(ResponseTypeEntry.IdToken) && conversation.scope.contains(ScopeToken.OpenId) then
                  generateIdTokenData(userId, conversation, amr, now)
                else
                  ZIO.none
              yield ConversationResult.Complete(
                redirectUri = conversation.redirectUri,
                state = conversation.state,
                code = code,
                sessionId = sessionId,
                idTokenData = idTokenData,
              )
            else
              ZIO.succeed(ConversationResult.IllegalState)
          yield result

    override def startPasskeyAssertion(
        authId: AuthId,
        conversation: ConversationRecord,
        step: ConversationStep.Credential,
        settings: PasskeySettings,
    ): Task[String] =
      webAuthnService.startAssertion(settings).foldZIO(
        ZIO.fail(_),
        ceremony =>
          val updatedStep = step.copy(
            passkeyRequest = Some(ceremony.request),
            passkeyFailed = false,
          )
          conversationRepository.overwrite(authId, conversation.copy(step = updatedStep))
            .as(ceremony.publicKeyOptions),
      )

    override def finishPasskeyAssertion(
      authId: AuthId,
      conversation: ConversationRecord,
      response: String,
      ipAddress: Option[String]
    ): Task[ConversationResult.Render] =
      conversation.step match
        case cred: ConversationStep.Credential if conversation.authFlow.passkey.isDefined =>
          cred.passkeyRequest match
            case None =>
              ZIO.succeed(ConversationResult.IllegalState)
            case Some(request) =>
              // The credential id is client-chosen and can be rotated to dodge throttling, so we
              // also throttle a server-controlled IP subject when available. The credential subject
              // still guards a specific registered credential against targeted brute force.
              // IP throttling is skipped entirely when neither X-Real-IP nor X-Forwarded-For is
              // present — we do not invent a shared fallback bucket.
              val ipSubjectOpt = ipAddress.map("ip:" + _)
              val recordIp = ipSubjectOpt.fold(ZIO.unit)(ip =>
                submissionLimiter.recordLimit(conversation.clientId, ip, ChallengeType.PasskeyAssertion).unit
              )
              val resetIp = ipSubjectOpt.fold(ZIO.unit)(ip =>
                submissionLimiter.reset(conversation.clientId, ip, ChallengeType.PasskeyAssertion)
              )
              webAuthnService.credentialIdFromResponse(response).flatMap:
                case None =>
                  // Malformed payload (no usable credential id). Throttle the IP if available and
                  // clear the stale request; if there is no IP we just re-render without recording.
                  val malformedSubjects = ipSubjectOpt.toList
                  if malformedSubjects.isEmpty then
                    renderStep(authId, conversation, cred.copy(passkeyRequest = None, passkeyFailed = true))
                  else
                    submissionLimiter.statusForSubjects(conversation.clientId, malformedSubjects, ChallengeType.PasskeyAssertion).flatMap:
                      case LimitStatus.Banned | LimitStatus.RateLimited(_) =>
                        accessDenied(authId, conversation)
                      case LimitStatus.Allowed =>
                        recordIp *>
                          renderStep(authId, conversation, cred.copy(passkeyRequest = None, passkeyFailed = true))
                case Some(credSubject) =>
                  val banSubjects = ipSubjectOpt.toList :+ credSubject
                  submissionLimiter.statusForSubjects(conversation.clientId, banSubjects, ChallengeType.PasskeyAssertion).flatMap:
                    case LimitStatus.Banned | LimitStatus.RateLimited(_) =>
                      accessDenied(authId, conversation)
                    case LimitStatus.Allowed =>
                      configService.getPasskeySettings(conversation.clientId).flatMap:
                        case None =>
                          ZIO.succeed(ConversationResult.IllegalState)
                        case Some(settings) =>
                          webAuthnService.finishAssertion(settings, request, response).foldZIO(
                            { _ =>
                              (recordIp zipPar submissionLimiter.recordLimit(conversation.clientId, credSubject, ChallengeType.PasskeyAssertion)) *>
                                renderStep(authId, conversation, cred.copy(passkeyRequest = None, passkeyFailed = true))
                            },
                            outcome =>
                              (resetIp zipPar submissionLimiter.reset(conversation.clientId, credSubject, ChallengeType.PasskeyAssertion)) *>
                                userRepository.find(outcome.userId).zipPar(
                                  passkeyRepository.findByCredentialIdAndUser(outcome.credentialId, outcome.userId)
                                ).flatMap:
                                  case (None, _) =>
                                    ZIO.succeed(ConversationResult.IllegalState)
                                  case (Some(user), passkeyOpt) =>
                                    Clock.instant.flatMap: now =>
                                      val keyType = passkeyOpt.fold(AuthMethodRef.swk)(pk =>
                                        if pk.deviceType == CredentialDeviceType.SingleDevice then AuthMethodRef.hwk
                                        else AuthMethodRef.swk
                                      )
                                      val passkeyMethods = Set(keyType, AuthMethodRef.user, AuthMethodRef.mfa)
                                      val updated = conversation.copy(
                                        userId = Some(outcome.userId),
                                        userEmail = user.email,
                                        userPhone = user.phone,
                                        userLogin = user.login,
                                        userClaims = Some(user.claims),
                                        amr = conversation.amr + (PassedAuthFactor.passkey -> PassedFactorRecord(now, passkeyMethods)),
                                      )
                                      conversationRepository.overwrite(authId, updated).flatMap:
                                        case true => offerPasskeyEnroll(authId, updated.copy(version = updated.version + 1))
                                        case false => ZIO.succeed(ConversationResult.IllegalState),
                          )
        case _ =>
          ZIO.succeed(ConversationResult.IllegalState)

    override def offerPasskeyEnroll(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Render] =
      conversation.userId match
        case None =>
          ZIO.succeed(ConversationResult.IllegalState)
        case Some(userId) =>
          configService.getPasskeySettings(conversation.clientId).flatMap:
            case None =>
              finish(authId, conversation)
            case Some(settings) =>
              passkeyRepository.listByUser(userId).flatMap: existing =>
                if existing.nonEmpty then finish(authId, conversation)
                else
                  val displayName: String =
                    conversation.userClaims.flatMap(_.fields.toMap.get("name"))
                      .collect { case zio.json.ast.Json.Str(v) => v }
                      .orElse(conversation.userEmail.map(_.toString))
                      .orElse(conversation.userPhone.map(_.toString))
                      .orElse(conversation.userLogin.map(_.toString))
                      .getOrElse(userId.toString)
                  webAuthnService.startRegistration(settings, userId, displayName).foldZIO(
                    _ => finish(authId, conversation),
                    ceremony =>
                      val enrollStep = ConversationStep.PasskeyEnroll(
                        request = ceremony.request,
                        publicKeyOptions = ceremony.publicKeyOptions,
                      )
                      renderStep(authId, conversation, enrollStep),
                  )

    override def finishPasskeyEnroll(
        authId: AuthId,
        conversation: ConversationRecord,
        enrollStep: ConversationStep.PasskeyEnroll,
        response: String,
        name: Option[String],
    ): Task[ConversationResult.Render] =
      conversation.userId match
        case None =>
          ZIO.succeed(ConversationResult.IllegalState)
        case Some(userId) =>
          configService.getPasskeySettings(conversation.clientId).flatMap:
            case None =>
              finish(authId, conversation)
            case Some(settings) =>
              webAuthnService.finishRegistration(settings, userId, enrollStep.request, response, name).foldZIO(
                error => renderStep(authId, conversation, enrollStep.copy(enrollFailed = true)),
                _ => finish(authId, conversation),
              )

    override def skipPasskey(authId: AuthId, conversation: ConversationRecord): Task[ConversationResult.Render] =
      finish(authId, conversation)

    private def generateIdTokenData(
        userId: UserId,
        conversation: ConversationRecord,
        amr: Set[AuthMethodRef],
        authTime: Instant,
    ): Task[Option[ConversationResult.IdTokenData]] =
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
          claims = userInfo.claims ++ AuthMethodRef.idTokenClaims(amr, Some(authTime)),
          clientId = conversation.clientId,
        ),
      )

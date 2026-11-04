package versola.oauth.conversation

import versola.audit.{AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.TestEnvConfig
import versola.auth.model.Password
import versola.oauth.authorize.AcrResolutionService
import versola.oauth.challenge.passkey.{PasskeyRepository, WebAuthnService}
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.{CheckPassword, PasswordReuseError}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFlow, ClientId, PassedAuthFactor, PrimaryCredential, ScopeToken}
import versola.oauth.conversation.limit.{LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.{SessionRepository, UserAgentRepository}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.model.{Login, UserId, UserRecord}
import versola.user.{UserRepository, UserService}
import versola.util.audit.{AuditOutcome, AuditUnavailableException}
import versola.util.{AuthPropertyGenerator, Email, Phone, SecureRandom, SecurityService, UnitSpecBase}
import zio.http.URL
import zio.json.ast
import zio.test.*

import java.time.Instant
import java.util.UUID

object PasswordConversationServiceSpec extends UnitSpecBase:

  val email = Email("test@example.com")
  val authId = AuthId(UUID.randomUUID())
  val userId = UserId(UUID.randomUUID())
  val password = Password("password123")

  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val scope = Set(ScopeToken("openid"), ScopeToken("profile"))
  val codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  val codeChallengeMethod = CodeChallengeMethod.S256

  val setPasswordStep = ConversationStep.SetPassword(
    factorIndex = 1,
    timesSubmitted = 0,
    rateLimitExceeded = false,
    passwordReused = false,
  )

  val passwordStep = ConversationStep.Password(
    timesSubmitted = 0,
    oldPasswordChangedAt = None,
    factorIndex = 0,
    rateLimitExceeded = false,
  )

  class Env:
    val otpService = stub[OtpService]
    val passwordService = stub[PasswordService]
    val conversationRepository = stub[ConversationRepository]
    val userRepository = stub[UserRepository]
    val authorizationCodeRepository = stub[AuthorizationCodeRepository]
    val sessionRepository = stub[SessionRepository]
    val authPropertyGenerator = stub[AuthPropertyGenerator]
    val securityService = stub[SecurityService]
    val userInfoService = stub[UserInfoService]
    val submissionLimiter = stub[SubmissionLimiter]
    val webAuthnService = stub[WebAuthnService]
    val passkeyRepository = stub[PasskeyRepository]
    val configService = stub[OAuthConfigurationService]
    val acrResolver = stub[AcrResolutionService]
    val config = TestEnvConfig.coreConfig
    val userAgentRepository = stub[UserAgentRepository]
    val secureRandom = stub[SecureRandom]
    val userService = stub[UserService]
    val consentService = stub[versola.oauth.consent.ConsentService]
    val audit = CapturingAuditEvents.shared()
    def serviceWith(events: versola.util.audit.AuditEvents) = ConversationService.Impl(
      otpService,
      passwordService,
      conversationRepository,
      userRepository,
      authorizationCodeRepository,
      sessionRepository,
      authPropertyGenerator,
      securityService,
      userInfoService,
      config,
      submissionLimiter,
      webAuthnService,
      passkeyRepository,
      configService,
      acrResolver,
      userAgentRepository,
      secureRandom,
      userService,
      consentService,
      versola.antifraud.AntifraudEvents.noop,
      versola.antifraud.ChallengeVerifier.off,
      stub[CodeAlternativeService],
      events,
    )
    val service = serviceWith(audit)

  /** The details every `login.step` of this conversation carries: the step, the conversation and what kind of sign-in it is. */
  private def step(name: String = "password") = AuthAuditDetails.LoginStep(step = name, conversationId = authId.toString, kind = "login")

  val baseRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = Some(codeChallenge),
    codeChallengeMethod = Some(codeChallengeMethod),
    state = None,
    userId = None,
    credential = None,
    step = ConversationStep.Credential(List(PrimaryCredential.phone), inlinePassword = false, passkey = false),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    responseMode = versola.oauth.authorize.model.ResponseMode.Query,
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = AuthFlow.default,
    registrationFlow = None,
    registrationStep = None,
    userAgent = None,
    userAgentCookie = None,
    version = 0,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,
    csrfToken = "test-csrf",
    priorSessionId = None,
    resources = Nil,
    authorizationDetails = None,
    grantedScope = None,
    promptConsent = false,
    dpopJkt = None,
  )

  val passwordRecord = baseRecord.copy(
    userId = Some(userId),
    credential = Some(Left(email)),
    step = passwordStep,
  )

  val login = Login("testuser")
  val credentialStep = ConversationStep.Credential(List(PrimaryCredential.phone), inlinePassword = false, passkey = false)
  val loginUser = UserRecord(
    userId,
    Some(Email("user@example.com")),
    None,
    Some(login),
    ast.Json.Obj("name" -> ast.Json.Str("Test User")),
    None,
  )

  val spec = suite("PasswordConversationService")(
    suite("prepareInitialPassword")(
      test("create password step when user exists and allowed") {
        val env = Env()
        val userEmail = Email("user@example.com")
        val userPhone = Phone("+1234567890")
        val userLogin = Login("testuser")
        val userClaims = ast.Json.Obj("name" -> ast.Json.Str("Test User"))
        val user = UserRecord(userId, Some(userEmail), Some(userPhone), Some(userLogin), userClaims, None)
        val resolvedRecord = baseRecord.copy(
          userId = Some(user.id),
          userEmail = user.email,
          userPhone = user.phone,
          userLogin = user.login,
          userClaims = Some(user.claims),
        )
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, resolvedRecord, Left(email), factorIndex = 0)
          overwriteCalls = env.conversationRepository.overwrite.calls
        yield assertTrue(
          result == ConversationResult.RenderStep(passwordStep),
          overwriteCalls.head._2.userEmail.contains(userEmail),
          overwriteCalls.head._2.userPhone.contains(userPhone),
          overwriteCalls.head._2.userLogin.contains(userLogin),
          overwriteCalls.head._2.userClaims.contains(userClaims),
        )
      },
      test("return AccessDenied when banned") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord.copy(userId = Some(userId)), Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("return AccessDenied when rate limited") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord.copy(userId = Some(userId)), Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("create password step when user not found") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(passwordStep))
      },
      test("deny access when only the credential subject is banned") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord.copy(userId = Some(userId)), Left(email), factorIndex = 0)
          checkedSubjects = env.submissionLimiter.statusForSubjects.calls.head._2.toSet
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          checkedSubjects == Set(userId.toString, email.toString),
        )
      },
      test("reports a write conflict when the conversation moved on") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.prepareInitialPassword(authId, baseRecord, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.WriteConflict)
      },
    ),
    suite("preparePasswordStep")(
      test("render fresh password step") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.preparePasswordStep(authId, passwordRecord, factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(passwordStep))
      },
      test("reports a write conflict when the conversation moved on") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.preparePasswordStep(authId, passwordRecord, factorIndex = 0)
        yield assertTrue(result == ConversationResult.WriteConflict)
      },
    ),
    suite("checkPassword")(
      test("return AccessDenied when record has no userId") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(baseRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("return AccessDenied when banned") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("re-render step with rate limit flag when rate limited") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(passwordStep.copy(rateLimitExceeded = true)))
      },
      test("return StepPassed when password is correct") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(result.isInstanceOf[ConversationResult.StepPassed])
      },
      test("re-render incremented step on failure when still allowed") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Failure)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            passwordStep.copy(timesSubmitted = 1, rateLimitExceeded = false),
          ),
        )
      },
      test("re-render step with rate limit flag on failure when rate limited") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Failure)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            passwordStep.copy(timesSubmitted = 1, rateLimitExceeded = true),
          ),
        )
      },
      test("return AccessDenied on failure when ban is applied") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Failure)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("re-render step with oldPasswordChangedAt when old password supplied") {
        val env = Env()
        val changedAt = Instant.parse("2024-01-01T00:00:00Z")
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.OldPassword(changedAt))
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            passwordStep.copy(
              timesSubmitted = 1,
              oldPasswordChangedAt = Some(changedAt),
              rateLimitExceeded = false,
            ),
          ),
        )
      },
      test("re-render step with oldPasswordChangedAt and rate limit flag when old password is rate limited") {
        val env = Env()
        val changedAt = Instant.parse("2024-01-01T00:00:00Z")
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.OldPassword(changedAt))
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            passwordStep.copy(
              timesSubmitted = 1,
              oldPasswordChangedAt = Some(changedAt),
              rateLimitExceeded = true,
            ),
          ),
        )
      },
      test("return AccessDenied when recording an old-password failure applies a ban") {
        val env = Env()
        val changedAt = Instant.parse("2024-01-01T00:00:00Z")
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.OldPassword(changedAt))
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("return StepPassed with needsPasswordChange when a temporary password is correct") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Temporary)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(
          result match
            case ConversationResult.StepPassed(updated) => updated.needsPasswordChange
            case _ => false,
        )
      },
      test("reports a write conflict when accepting a temporary password cannot be persisted") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Temporary)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.WriteConflict)
      },
      test("reports a write conflict when accepting a correct password cannot be persisted") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.WriteConflict)
      },
      test("re-render step with temporaryExpired flag when the temporary password has expired") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.TemporaryExpired)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            passwordStep.copy(timesSubmitted = 1, temporaryExpired = true, rateLimitExceeded = false),
          ),
        )
      },
      test("re-render step with temporaryExpired and rate limit flags when rate limited") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.TemporaryExpired)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            passwordStep.copy(timesSubmitted = 1, temporaryExpired = true, rateLimitExceeded = true),
          ),
        )
      },
      test("return AccessDenied when recording a temporary-expired failure applies a ban") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.TemporaryExpired)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("deny access when only the credential subject is banned") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
          checkedSubjects = env.submissionLimiter.statusForSubjects.calls.head._2.toSet
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          checkedSubjects == Set(userId.toString, email.toString),
        )
      },
      test("record failure for both user and credential subjects on failure") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Failure)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
          failureSubjects = env.submissionLimiter.recordLimitAll.calls.head._2.toSet
          failureTimes = env.submissionLimiter.recordLimitAll.times
        yield assertTrue(
          failureTimes == 1,
          failureSubjects == Set(userId.toString, email.toString),
        )
      },
    ),
    suite("checkLoginPassword")(
      test("return BadRequest when step is not Credential") {
        val env = Env()
        for
          result <- env.service.checkLoginPassword(authId, passwordRecord, login, password)
        yield assertTrue(result == ConversationResult.BadRequest)
      },
      test("record failure against login and re-render loginFailed when user not found") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(None)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
          recordedSubjects = env.submissionLimiter.recordLimit.calls.map(_._2).toSet
        yield assertTrue(
          result == ConversationResult.RenderStep(credentialStep.copy(loginFailed = true)),
          recordedSubjects == Set(login),
        )
      },
      test("return StepPassed and set user fields when password is correct") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
          overwriteCalls = env.conversationRepository.overwrite.calls
        yield assertTrue(
          result.isInstanceOf[ConversationResult.StepPassed],
          overwriteCalls.head._2.userId.contains(userId),
          overwriteCalls.head._2.userLogin.contains(login),
          overwriteCalls.head._2.userClaims.contains(loginUser.claims),
        )
      },
      test("reports a write conflict when accepting a correct password cannot be persisted") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
        yield assertTrue(result == ConversationResult.WriteConflict)
      },
      test("return StepPassed with needsPasswordChange when a temporary password is correct") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Temporary)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
        yield assertTrue(
          result match
            case ConversationResult.StepPassed(updated) => updated.needsPasswordChange && updated.userId.contains(userId)
            case _ => false,
        )
      },
      test("reports a write conflict when accepting a temporary password cannot be persisted") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Temporary)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
        yield assertTrue(result == ConversationResult.WriteConflict)
      },
      test("deny access when either subject is banned, checking both subjects in one query") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
          checkedSubjects = env.submissionLimiter.statusForSubjects.calls.map(_._2).flatten.toSet
          checkedTimes = env.submissionLimiter.statusForSubjects.times
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          checkedSubjects == Set(login, userId.toString),
          checkedTimes == 1,
        )
      },
      test("re-render loginFailed when rate limited") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
        yield assertTrue(result == ConversationResult.RenderStep(credentialStep.copy(loginFailed = true)))
      },
      test("record failure for both subjects and re-render loginFailed on wrong password") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Failure)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
          recordedSubjects = env.submissionLimiter.recordLimitAll.calls.map(_._2).flatten.toSet
          recordedTimes = env.submissionLimiter.recordLimitAll.times
        yield assertTrue(
          result == ConversationResult.RenderStep(credentialStep.copy(loginFailed = true)),
          recordedSubjects == Set(login, userId.toString),
          recordedTimes == 1,
        )
      },
      test("deny access when recording the failure applies a ban") {
        val env = Env()
        for
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Failure)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkLoginPassword(authId, baseRecord, login, password)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
    ),
    suite("finish")(
    ),
    suite("offerSetPassword")(
      test("renders the set-password step after the configured primary factors") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.offerSetPassword(authId, passwordRecord)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            ConversationStep.SetPassword(
              factorIndex = passwordRecord.authFlow.primary.factors.length,
              timesSubmitted = 0,
              rateLimitExceeded = false,
              passwordReused = false,
            ),
          ),
        )
      },
      test("reports a write conflict when the conversation moved on") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.offerSetPassword(authId, passwordRecord)
        yield assertTrue(result == ConversationResult.WriteConflict)
      },
    ),
    suite("setNewPassword")(
      test("denies access when the conversation never resolved a user") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.setNewPassword(baseRecord, setPasswordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("denies access when the subject is banned") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.setNewPassword(passwordRecord, setPasswordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("re-renders with the rate limit flag when the subject is rate limited") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.RateLimited(30))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.setNewPassword(passwordRecord, setPasswordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(setPasswordStep.copy(rateLimitExceeded = true)),
        )
      },
      test("passes the step and clears needsPasswordChange once the password is accepted") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.setPassword.succeedsWith(())
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.setNewPassword(
            passwordRecord.copy(needsPasswordChange = true),
            setPasswordStep,
            password,
            authId,
          )
        yield assertTrue(
          result match
            case ConversationResult.StepPassed(updated) =>
              !updated.needsPasswordChange && updated.amr.contains(PassedAuthFactor.password)
            case _ => false,
        )
      },
      test("reports a write conflict when the accepted password cannot be persisted") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.setPassword.succeedsWith(())
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.setNewPassword(passwordRecord, setPasswordStep, password, authId)
        yield assertTrue(result == ConversationResult.WriteConflict)
      },
      test("re-renders with the reuse flag when the new password repeats an old one") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.setPassword.failsWith(PasswordReuseError(3))
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.setNewPassword(passwordRecord, setPasswordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            setPasswordStep.copy(timesSubmitted = 1, passwordReused = true),
          ),
        )
      },
      test("flags both reuse and the rate limit when the retry budget runs out") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.setPassword.failsWith(PasswordReuseError(3))
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.RateLimited(30))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.setNewPassword(passwordRecord, setPasswordStep, password, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            setPasswordStep.copy(timesSubmitted = 1, passwordReused = true, rateLimitExceeded = true),
          ),
        )
      },
      test("denies access when repeated reuse attempts earn a ban") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.setPassword.failsWith(PasswordReuseError(3))
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.setNewPassword(passwordRecord, setPasswordStep, password, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("propagates an unexpected failure from the password service") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.setPassword.failsWith(new RuntimeException("boom"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.setNewPassword(passwordRecord, setPasswordStep, password, authId).exit
        yield assertTrue(result.isFailure)
      },
    ),
    suite("audit log")(
      test("a correct password records login.step success, with the user and the conversation") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
          drafts <- env.audit.recorded
        yield assertTrue(
          drafts.map(_.kind) == List(AuthAuditKinds.LoginStep),
          drafts.head.outcome == AuditOutcome.success,
          drafts.head.userId == passwordRecord.userId,
          drafts.head.details == step(),
          // the password is never in the event, nor any hash of it
          !drafts.head.details.toJson.toString.contains(password),
        )
      },
      test("a wrong password records login.step failure invalid_password") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Failure)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
          drafts <- env.audit.recorded
        yield assertTrue(drafts.map(d => (d.kind, d.outcome, d.reason)) == List((
          AuthAuditKinds.LoginStep,
          AuditOutcome.failure,
          Some("invalid_password"),
        )))
      },
      test("a retired or expired temporary password is a failure with its own reason") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.TemporaryExpired)
          _ <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.OldPassword(Instant.EPOCH))
          _ <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
          drafts <- env.audit.recorded
        yield assertTrue(drafts.map(_.reason) == List(Some("temporary_expired"), Some("old_password")))
      },
      test("a banned subject records login.step denied locked, and a rate limited one denied throttled") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.service.checkPassword(passwordRecord, passwordStep, password, authId)
          drafts <- env.audit.recorded
        yield assertTrue(
          drafts.map(d => (d.outcome, d.reason)) == List((AuditOutcome.denied, Some("locked")), (AuditOutcome.denied, Some("throttled"))),
          // the password was not even looked at
          env.passwordService.verifyPassword.calls.isEmpty,
        )
      },
      test("login with a login and password records the same events, and an unknown login a failure with no user") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.userRepository.findByLogin.succeedsWith(None)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.service.checkLoginPassword(authId, baseRecord, login, password)
          unknown <- env.audit.recorded
          _ <- env.audit.clear
          _ <- env.userRepository.findByLogin.succeedsWith(Some(loginUser))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.service.checkLoginPassword(authId, baseRecord, login, password)
          known <- env.audit.recorded
        yield assertTrue(
          unknown.map(d => (d.outcome, d.reason, d.userId)) == List((AuditOutcome.failure, Some("unknown_login"), None)),
          // the user is the one the login named, though the conversation had none yet
          known.map(d => (d.outcome, d.userId)) == List((AuditOutcome.success, Some(userId.asInstanceOf[java.util.UUID]))),
          // the login the person typed is not in the event
          !unknown.head.details.toJson.toString.contains(login),
        )
      },
      test("a new password accepted at sign-in records account.password_set, and a reused one records nothing") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.passwordService.setPassword.succeedsWith(())
          _ <- env.service.setNewPassword(passwordRecord.copy(needsPasswordChange = true), setPasswordStep, password, authId)
          accepted <- env.audit.recorded
          _ <- env.audit.clear
          _ <- env.passwordService.setPassword.failsWith(versola.oauth.challenge.password.model.PasswordReuseError(5))
          _ <- env.submissionLimiter.recordLimitAll.succeedsWith(LimitStatus.Allowed)
          _ <- env.service.setNewPassword(passwordRecord.copy(needsPasswordChange = true), setPasswordStep, password, authId)
          reused <- env.audit.recorded
        yield assertTrue(
          accepted.map(d => (d.kind, d.details)) == List((AuthAuditKinds.AccountPasswordSet, AuthAuditDetails.Account(via = "login_flow"))),
          reused.isEmpty,
        )
      },
      test("the step is not completed when the log cannot take the event") {
        val env = Env()
        val refusing = CapturingAuditEvents.shared(unavailable = true)
        for
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.serviceWith(refusing).checkPassword(passwordRecord, passwordStep, password, authId).either
        yield assertTrue(result == Left(AuditUnavailableException), env.conversationRepository.overwrite.calls.isEmpty)
      },
    ),
  )

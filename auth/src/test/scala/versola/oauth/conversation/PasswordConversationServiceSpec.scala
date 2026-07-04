package versola.oauth.conversation

import versola.auth.TestEnvConfig
import versola.auth.model.Password
import versola.oauth.challenge.passkey.{PasskeyRepository, WebAuthnService}
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.CheckPassword
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFlow, ClientId, PrimaryCredential, ScopeToken}
import versola.oauth.conversation.limit.{LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.SessionRepository
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.user.model.{Login, UserId, UserRecord}
import versola.util.{AuthPropertyGenerator, Email, Phone, SecurityService, UnitSpecBase}
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
    val config = TestEnvConfig.coreConfig
    val service = ConversationService.Impl(
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
    )

  val baseRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    state = None,
    userId = None,
    credential = None,
    step = ConversationStep.Credential(List(PrimaryCredential.phone), inlinePassword = false, passkey = false),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = AuthFlow.default,
    userAgent = None,
    version = 0,
    amr = Map.empty,
    needsPasswordChange = false,
    expectedUserId = None,
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
        for
          _ <- env.userRepository.findByCredential.succeedsWith(Some(user))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord, Left(email), factorIndex = 0)
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
          _ <- env.userRepository.findByCredential.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("return AccessDenied when rate limited") {
        val env = Env()
        for
          _ <- env.userRepository.findByCredential.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("create password step when user not found") {
        val env = Env()
        for
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(passwordStep))
      },
      test("deny access when only the credential subject is banned") {
        val env = Env()
        for
          _ <- env.userRepository.findByCredential.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialPassword(authId, baseRecord, Left(email), factorIndex = 0)
          checkedSubjects = env.submissionLimiter.statusForSubjects.calls.head._2.toSet
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          checkedSubjects == Set(userId.toString, email.toString),
        )
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
    ),
    suite("checkPassword")(
      test("return IllegalState when record has no userId") {
        val env = Env()
        for
          result <- env.service.checkPassword(baseRecord, passwordStep, password, authId)
        yield assertTrue(result == ConversationResult.IllegalState)
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
      test("return IllegalState when step is not Credential") {
        val env = Env()
        for
          result <- env.service.checkLoginPassword(authId, passwordRecord, login, password)
        yield assertTrue(result == ConversationResult.IllegalState)
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
      test("deny access when authenticated user does not match expectedUserId") {
        val env = Env()
        val expectedUser = UserId(UUID.randomUUID())
        val differentUser = UserId(UUID.randomUUID())
        val recordWithMismatch = baseRecord.copy(
          userId = Some(differentUser),
          expectedUserId = Some(expectedUser.toString),
        )
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finish(authId, recordWithMismatch)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
    ),
  )

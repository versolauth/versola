package versola.oauth.conversation

import versola.auth.model.{OtpCode, Password}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFactor, AuthFactorType, AuthFlow, AuthMethodRef, ClientId, OtpType, PassedAuthFactor, PassedFactorRecord, PrimaryAuthFlow, PrimaryCredential, RegistrationCredential, RegistrationFlow, RegistrationStep, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, Error}
import zio.{ZIO, Exit}
import versola.oauth.model.{AuthorizationCode, CodeChallenge, CodeChallengeMethod, State, UserAgentData}
import versola.oauth.session.model.{SessionId, UserAgentDetails, UserAgentId}
import versola.role.model.RoleId
import versola.user.UserRepository
import versola.user.model.{Login, UserId, UserRecord}
import versola.util.{Email, Phone, SecureRandom, UnitSpecBase}
import zio.http.URL
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

object ConversationRouterSpec extends UnitSpecBase:

  val authId = AuthId(UUID.randomUUID())
  val testUserAgentId = UserAgentId(UUID.randomUUID())
  val testUserId = UserId(UUID.randomUUID())
  val email = Email("test@example.com")
  val phone = Phone("+1234567890")
  val otpCode = OtpCode("123456")

  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val scope = Set(ScopeToken("openid"), ScopeToken("profile"))
  val codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  val codeChallengeMethod = CodeChallengeMethod.S256

  val otp = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(otpCode)),
    timesRequested = 1,
    timesSubmitted = 0,
    factorIndex = 0,
    rateLimitExceeded = false,
    lockedSeconds = 0,
    lastSentAt = None,
  )

  val conversationResult = ConversationResult.RenderStep(otp)

  val otpAuthFlow = AuthFlow(
    primary = PrimaryAuthFlow(
      credentials = List(PrimaryCredential.phone),
      inlinePassword = false,
      factors = List(AuthFactor(`type` = AuthFactorType.otp, required = true)),
    ),
    passkey = None,
    equivalents = Map.empty,
    otpType = OtpType.sms,
  )

  val initialRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    state = Some(State("test-state")),
    userId = None,
    credential = None,
    step = ConversationStep.Credential(List(PrimaryCredential.phone), inlinePassword = false, passkey = false, registration = false, passkeyRequest = None, passkeyFailed = false, loginFailed = false),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = otpAuthFlow,
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
  )

  val otpRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = scope,
    codeChallenge = codeChallenge,
    codeChallengeMethod = codeChallengeMethod,
    state = Some(State("test-state")),
    userId = None,
    credential = Some(Left(email)),
    step = otp,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = otpAuthFlow,
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
  )

  val login = Login("testuser")
  val password = Password("password123")

  val loginFlow = AuthFlow(
    primary = PrimaryAuthFlow(
      credentials = List(PrimaryCredential.login),
      inlinePassword = true,
      factors = List.empty,
    ),
    passkey = None,
    equivalents = Map.empty,
    otpType = OtpType.sms,
  )

  val loginRecord = initialRecord.copy(authFlow = loginFlow)

  val roleId = RoleId("user")

  /** A conversation for a client that offers registration alongside sign-in. */
  val registrationRecord = initialRecord.copy(
        registrationFlow = Some(RegistrationFlow(RegistrationCredential.phone, List(RegistrationStep.Otp(), RegistrationStep.SetPassword()), Set(roleId))),
  )

  class Env:
    val conversationRepository = stub[ConversationRepository]
    val otpConversationService = stub[ConversationService]
    val configService = stub[OAuthConfigurationService]
    val secureRandom = stub[SecureRandom]
    val userRepository = stub[UserRepository]
    val router = ConversationRouter.Impl(
      conversationRepository,
      otpConversationService,
      configService,
      secureRandom,
      userRepository,
    )

  val spec = suite("ConversationRouter")(
    suite("getConversation")(
      test("return conversation record when it exists") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(otpRecord))
          result <- env.router.getConversation(authId)
        yield assertTrue(result.contains(otpRecord))
      },
      test("return None when record doesn't exist") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(None)
          result <- env.router.getConversation(authId)
        yield assertTrue(result.isEmpty)
      },
      test("fail with ServiceUnavailable when conversation lookup fails") {
        val env = Env()
        val boom = new RuntimeException("db down")
        for
          _ <- env.otpConversationService.find.failsWith(boom)
          exit <- env.router.getConversation(authId).exit
        yield assertTrue(exit == Exit.fail(Error.ServiceUnavailable))
      },
    ),
    suite("submit")(
      test("fail with ConversationExpired when conversation does not exist") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(None)
          exit <- env.router.submit(authId, EmailSubmission(email, "test-csrf"), None, None).exit
        yield assertTrue(exit == Exit.fail(Error.ConversationExpired))
      },
      test("handle email submission") {
        val env = Env()
        val submission = EmailSubmission(email, "test-csrf")
        val emailRecord = initialRecord.copy(
          step = ConversationStep.Credential(List(PrimaryCredential.email), inlinePassword = false, passkey = false),
          authFlow = otpAuthFlow.copy(
            primary = otpAuthFlow.primary.copy(credentials = List(PrimaryCredential.email)),
            otpType = OtpType.email,
          ),
        )
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(emailRecord))
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, record) <- env.router.submit(authId, submission, None, None)
          prepareTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == conversationResult,
          record == emailRecord,
          prepareTimes == 1,
        )
      },
      test("email submission with locked identity (userId set) returns access_denied") {
        val env = Env()
        val lockedRecord = initialRecord.copy(userId = Some(UserId(UUID.randomUUID())))
        val accessDeniedResult = ConversationResult.RenderStep(ConversationStep.AccessDenied)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(lockedRecord))
          _ <- env.otpConversationService.accessDenied.succeedsWith(accessDeniedResult)
          (result, _) <- env.router.submit(authId, EmailSubmission(email, "test-csrf"), None, None)
          accessDeniedTimes = env.otpConversationService.accessDenied.times
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == accessDeniedResult,
          accessDeniedTimes == 1,
          prepareOtpTimes == 0,
        )
      },
      test("phone submission with locked identity (userId set) returns access_denied") {
        val env = Env()
        val lockedRecord = initialRecord.copy(userId = Some(UserId(UUID.randomUUID())))
        val accessDeniedResult = ConversationResult.RenderStep(ConversationStep.AccessDenied)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(lockedRecord))
          _ <- env.otpConversationService.accessDenied.succeedsWith(accessDeniedResult)
          (result, _) <- env.router.submit(authId, PhoneSubmission(phone, "test-csrf"), None, None)
          accessDeniedTimes = env.otpConversationService.accessDenied.times
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == accessDeniedResult,
          accessDeniedTimes == 1,
          prepareOtpTimes == 0,
        )
      },
      test("handle phone submission") {
        val env = Env()
        val submission = PhoneSubmission(phone, "test-csrf")
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(initialRecord))
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, record) <- env.router.submit(authId, submission, None, None)
          prepareTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == conversationResult,
          record == initialRecord,
          prepareTimes == 1,
        )
      },
      test("return ServiceUnavailable when conversation lookup fails") {
        val env = Env()
        val boom = new RuntimeException("db down")
        for
          _ <- env.otpConversationService.find.failsWith(boom)
          exit <- env.router.submit(authId, EmailSubmission(email, "test-csrf"), None, None).exit
        yield assertTrue(exit == Exit.fail(Error.ServiceUnavailable))
      },
      test("handle OTP submission and complete conversation on success") {
        val env = Env()
        val submission = OtpSubmission(otpCode, "test-csrf")
        val successResult = ConversationResult.StepPassed(otpRecord)
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId: SessionId = SessionId(Array.fill(32)(2.toByte))
        val completeResult = ConversationResult.Complete(redirectUri, Some(State("test-state")), testCode, testSessionId, None, testUserAgentId, UserAgentData(None, testUserId, UserAgentDetails.parse(None)))
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(otpRecord))
          _ <- env.otpConversationService.checkOtp.succeedsWith(successResult)
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          (result, _) <- env.router.submit(authId, submission, None, None)
          checkOtpTimes = env.otpConversationService.checkOtp.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == completeResult,
          checkOtpTimes == 1,
          finishTimes == 1,
        )
      },
      test("return BadRequest when the submission does not match the current step") {
        val env = Env()
        val submission = OtpSubmission(otpCode, "test-csrf")
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(initialRecord))
          (result, _) <- env.router.submit(authId, submission, None, None)
        yield assertTrue(result == ConversationResult.BadRequest)
      },
      test("skip OTP factor and finish when passkey satisfies it via equivalents") {
        val env = Env()
        val now = Instant.now()
        val flowWithEquivalents = AuthFlow(
          primary = PrimaryAuthFlow(
            credentials = List(PrimaryCredential.phone),
            inlinePassword = false,
            factors = List(AuthFactor(`type` = AuthFactorType.otp, required = true)),
          ),
          passkey = None,
          equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp)),
          otpType = OtpType.sms,
        )
        val recordWithPasskeyAmr = initialRecord.copy(
          authFlow = flowWithEquivalents,
          amr = Map(PassedAuthFactor.passkey -> PassedFactorRecord(now, Set(AuthMethodRef.swk, AuthMethodRef.user, AuthMethodRef.mfa))),
        )
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = SessionId(Array.fill(32)(2.toByte))
        val completeResult = ConversationResult.Complete(redirectUri, Some(State("test-state")), testCode, testSessionId, None, testUserAgentId, UserAgentData(None, testUserId, UserAgentDetails.parse(None)))
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(recordWithPasskeyAmr))
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          (result, _) <- env.router.submit(authId, PhoneSubmission(phone, "test-csrf"), None, None)
          finishTimes = env.otpConversationService.finish.times
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == completeResult,
          finishTimes == 1,
          prepareOtpTimes == 0,
        )
      },
      test("handle login-password submission and finish when no further factors remain") {
        val env = Env()
        val submission = LoginPasswordSubmission(login, password, "test-csrf")
        val successResult = ConversationResult.StepPassed(loginRecord)
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId: SessionId = SessionId(Array.fill(32)(2.toByte))
        val completeResult = ConversationResult.Complete(redirectUri, Some(State("test-state")), testCode, testSessionId, None, testUserAgentId, UserAgentData(None, testUserId, UserAgentDetails.parse(None)))
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(loginRecord))
          _ <- env.otpConversationService.checkLoginPassword.succeedsWith(successResult)
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          (result, _) <- env.router.submit(authId, submission, None, None)
          checkTimes = env.otpConversationService.checkLoginPassword.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == completeResult,
          checkTimes == 1,
          finishTimes == 1,
        )
      },
      test("route to the configured email OTP after login-password authentication") {
        val env = Env()
        val loginOtpFlow = loginFlow.copy(
          primary = loginFlow.primary.copy(
            factors = List(AuthFactor(`type` = AuthFactorType.otp, required = true)),
          ),
          otpType = OtpType.email,
        )
        val loginOtpRecord = loginRecord.copy(
          authFlow = loginOtpFlow,
          userEmail = Some(email),
          userPhone = Some(phone),
        )
        val successResult = ConversationResult.StepPassed(loginOtpRecord)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(loginRecord))
          _ <- env.otpConversationService.checkLoginPassword.succeedsWith(successResult)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          _ <- env.router.submit(authId, LoginPasswordSubmission(login, password, "test-csrf"), None, None)
          call = env.otpConversationService.prepareInitialOtp.calls.last
        yield assertTrue(
          call._3 == Left(email),
          call._2.authFlow.otpType == OtpType.email,
        )
      },
      test("return the render result directly when login-password does not pass") {
        val env = Env()
        val submission = LoginPasswordSubmission(login, password, "test-csrf")
        val renderResult = ConversationResult.RenderStep(loginRecord.step)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(loginRecord))
          _ <- env.otpConversationService.checkLoginPassword.succeedsWith(renderResult)
          (result, _) <- env.router.submit(authId, submission, None, None)
          checkTimes = env.otpConversationService.checkLoginPassword.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == renderResult,
          checkTimes == 1,
          finishTimes == 0,
        )
      },
      test("submit with wrong csrf token fails with BadRequest") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(otpRecord))
          result <- env.router.submit(authId, OtpSubmission(otpCode, "wrong-csrf"), None, None).exit
        yield assertTrue(result == Exit.fail(Error.BadRequest))
      },
    ),
    suite("registration")(
      test("a credential type not configured by the auth flow is denied before user lookup") {
        val env = Env()
        val accessDeniedResult = ConversationResult.RenderStep(ConversationStep.AccessDenied)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(registrationRecord))
          _ <- env.otpConversationService.accessDenied.succeedsWith(accessDeniedResult)
          (result, _) <- env.router.submit(authId, EmailSubmission(email, "test-csrf"), None, None)
          startTimes = env.otpConversationService.startRegistration.times
          lookupTimes = env.userRepository.findByCredential.times
        yield assertTrue(
          result == accessDeniedResult,
          startTimes == 0,
          lookupTimes == 0,
        )
      },
      test("an allowed non-registration credential uses the indistinguishable authentication path") {
        val env = Env()
        val multiCredentialRecord = registrationRecord.copy(
          authFlow = otpAuthFlow.copy(
            primary = otpAuthFlow.primary.copy(
              credentials = List(PrimaryCredential.phone, PrimaryCredential.email),
            ),
          ),
        )
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(multiCredentialRecord))
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, _) <- env.router.submit(authId, EmailSubmission(email, "test-csrf"), None, None)
          startTimes = env.otpConversationService.startRegistration.times
          prepareTimes = env.otpConversationService.prepareInitialOtp.times
          accessDeniedTimes = env.otpConversationService.accessDenied.times
        yield assertTrue(
          result == conversationResult,
          startTimes == 0,
          prepareTimes == 1,
          accessDeniedTimes == 0,
        )
      },
      test("a new credential starts the registration flow on its first step") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(registrationRecord))
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpConversationService.startRegistration.succeedsWith(
                 RegistrationEntry.Registering(
                   registrationRecord.copy(registrationStep = Some(0), credential = Some(Right(phone))),
                 ),
               )
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, _) <- env.router.submit(authId, PhoneSubmission(phone, "test-csrf"), None, None)
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == conversationResult,
          prepareOtpTimes == 1,
        )
      },
      test("returns a write conflict when registration loses the overwrite race") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(registrationRecord))
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpConversationService.startRegistration.succeedsWith(ConversationResult.WriteConflict)
          (result, _) <- env.router.submit(authId, PhoneSubmission(phone, "test-csrf"), None, None)
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          result == ConversationResult.WriteConflict,
          prepareOtpTimes == 0,
        )
      },
      test("an unknown credential enters registration when the flow is enabled") {
        val env = Env()
        val registering = registrationRecord.copy(
          registrationStep = Some(0),
          credential = Some(Right(phone)),
        )
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(registrationRecord))
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpConversationService.startRegistration.succeedsWith(RegistrationEntry.Registering(registering))
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, _) <- env.router.submit(authId, PhoneSubmission(phone, "test-csrf"), None, None)
        yield assertTrue(result == conversationResult)
      },
      test("a credential on the registration path enters registration") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(registrationRecord))
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpConversationService.startRegistration.succeedsWith(
                 RegistrationEntry.Registering(registrationRecord.copy(registrationStep = Some(0), credential = Some(Right(phone)))),
               )
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, _) <- env.router.submit(authId, PhoneSubmission(phone, "test-csrf"), None, None)
        yield assertTrue(
          result == conversationResult,
        )
      },
      test("an existing credential uses authentication instead of registration") {
        val env = Env()
        val existingUser = UserRecord(testUserId, None, Some(phone), None, Json.Obj(), None)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(registrationRecord))
          _ <- env.userRepository.findByCredential.succeedsWith(Some(existingUser))
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          (result, _) <- env.router.submit(authId, PhoneSubmission(phone, "test-csrf"), None, None)
          startTimes = env.otpConversationService.startRegistration.times
        yield assertTrue(
          result == conversationResult,
          startTimes == 0,
        )
      },
      test("credential submission with an identity already locked in is denied") {
        val env = Env()
        val lockedRecord = registrationRecord.copy(userId = Some(UserId(UUID.randomUUID())))
        val accessDeniedResult = ConversationResult.RenderStep(ConversationStep.AccessDenied)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(lockedRecord))
          _ <- env.otpConversationService.accessDenied.succeedsWith(accessDeniedResult)
          (result, _) <- env.router.submit(authId, PhoneSubmission(phone, "test-csrf"), None, None)
          startTimes = env.otpConversationService.startRegistration.times
        yield assertTrue(
          result == accessDeniedResult,
          startTimes == 0,
        )
      },
      test("a failed otp does not create or reserve the registration account") {
        val env = Env()
        val pending = registrationRecord.copy(
          credential = Some(Right(phone)),
          step = otp,
          registrationStep = Some(0),
        )
        val failed = ConversationResult.RenderStep(otp.copy(timesSubmitted = 1))
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(pending))
          _ <- env.otpConversationService.checkOtp.succeedsWith(failed)
          (result, _) <- env.router.submit(authId, OtpSubmission(otpCode, "test-csrf"), None, None)
          registerTimes = env.otpConversationService.registerVerifiedUser.times
        yield assertTrue(
          result == failed,
          registerTimes == 0,
        )
      },
      test("a passed otp advances to the next registration step rather than the auth factors") {
        val env = Env()
        val pending = registrationRecord.copy(
          credential = Some(Right(phone)),
          step = otp,
          registrationStep = Some(0),
          amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(Instant.now(), Set(AuthMethodRef.otp))),
        )
        val registered = pending.copy(
          userId = Some(UserId(UUID.randomUUID())),
          registrationStep = Some(1),
        )
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(pending))
          _ <- env.otpConversationService.checkOtp.succeedsWith(ConversationResult.StepPassed(pending))
          _ <- env.otpConversationService.registerVerifiedUser.succeedsWith(RegistrationEntry.Registering(registered))
          _ <- env.otpConversationService.offerSetPassword.succeedsWith(conversationResult)
          (result, _) <- env.router.submit(authId, OtpSubmission(otpCode, "test-csrf"), None, None)
          registerTimes = env.otpConversationService.registerVerifiedUser.times
          setPasswordTimes = env.otpConversationService.offerSetPassword.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == conversationResult,
          registerTimes == 1,
          setPasswordTimes == 1,
          finishTimes == 0,
        )
      },
      test("the conversation finishes once the last registration step passes") {
        val env = Env()
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = SessionId(Array.fill(32)(2.toByte))
        val testUserId = UserId(UUID.randomUUID())
        val completeResult = ConversationResult.Complete(
          redirectUri,
          Some(State("test-state")),
          testCode,
          testSessionId,
          None,
          testUserAgentId,
          UserAgentData(None, testUserId, UserAgentDetails.parse(None)),
        )
        // Positioned on the final step, so advancing exhausts the flow.
        val otpOnlyFlow = RegistrationFlow(RegistrationCredential.phone, List(RegistrationStep.Otp()), Set(roleId))
        val pending = registrationRecord.copy(
          credential = Some(Right(phone)),
          step = otp,
          registrationStep = Some(0),
          registrationFlow = Some(otpOnlyFlow),
          amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(Instant.now(), Set(AuthMethodRef.otp))),
        )
        val registered = pending.copy(
          userId = Some(testUserId),
          registrationStep = Some(1),
        )
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(pending))
          _ <- env.otpConversationService.checkOtp.succeedsWith(ConversationResult.StepPassed(pending))
          _ <- env.otpConversationService.registerVerifiedUser.succeedsWith(RegistrationEntry.Registering(registered))
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          (result, _) <- env.router.submit(authId, OtpSubmission(otpCode, "test-csrf"), None, None)
          registerTimes = env.otpConversationService.registerVerifiedUser.times
          finishTimes = env.otpConversationService.finish.times
        yield assertTrue(
          result == completeResult,
          registerTimes == 1,
          finishTimes == 1,
        )
      },
    ),
    suite("advance")(
      test("routes to OTP step from the configured flow when factor is not yet satisfied") {
        val env = Env()
        val recordWithoutCredential = otpRecord.copy(credential = None, userPhone = Some(phone))
        for
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          _ <- env.router.advance(authId, recordWithoutCredential)
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(prepareOtpTimes == 1)
      },
      test("routes to OTP using email from the configured email flow") {
        val env = Env()
        val emailFlow = otpAuthFlow.copy(
          primary = otpAuthFlow.primary.copy(credentials = List(PrimaryCredential.email)),
          otpType = OtpType.email,
        )
        val recordWithoutCredential = otpRecord.copy(
          authFlow = emailFlow,
          credential = None,
          userEmail = Some(email),
        )
        for
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          _ <- env.router.advance(authId, recordWithoutCredential)
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(prepareOtpTimes == 1)
      },
      test("returns AccessDenied when OTP is required but credential is missing") {
        val env = Env()
        val accessDeniedResult = ConversationResult.RenderStep(ConversationStep.AccessDenied)
        val recordWithoutCredential = otpRecord.copy(credential = None)
        for
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.accessDenied.succeedsWith(accessDeniedResult)
          _ <- env.router.advance(authId, recordWithoutCredential)
          accessDeniedTimes = env.otpConversationService.accessDenied.times
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          accessDeniedTimes == 1,
          prepareOtpTimes == 0,
        )
      },
      test("route to the configured SMS OTP for a known user with multiple credentials") {
        val env = Env()
        val multiCredentialFlow = otpAuthFlow.copy(
          primary = otpAuthFlow.primary.copy(credentials = List(PrimaryCredential.email, PrimaryCredential.phone)),
          otpType = OtpType.sms,
        )
        val recordWithoutCredential = otpRecord.copy(
          authFlow = multiCredentialFlow,
          credential = None,
          userEmail = Some(email),
          userPhone = Some(phone),
        )
        for
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.prepareInitialOtp.succeedsWith(conversationResult)
          _ <- env.router.advance(authId, recordWithoutCredential)
          call = env.otpConversationService.prepareInitialOtp.calls.last
        yield assertTrue(
          call._3 == Right(phone),
          call._2.authFlow.otpType == OtpType.sms,
        )
      },
      test("skips OTP factor already in amr and routes to password") {
        val env = Env()
        val now = Instant.now()
        val twoFactorFlow = AuthFlow(
          primary = PrimaryAuthFlow(
            credentials = List(PrimaryCredential.phone),
            inlinePassword = false,
            factors = List(
              AuthFactor(`type` = AuthFactorType.otp, required = true),
              AuthFactor(`type` = AuthFactorType.password, required = true),
            ),
          ),
          passkey = None,
          equivalents = Map.empty,
          otpType = OtpType.sms,
        )
        val record = otpRecord.copy(
          authFlow = twoFactorFlow,
          amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))),
        )
        val passwordResult = ConversationResult.RenderStep(ConversationStep.Password(0, None, 1, false, false))
        for
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.preparePasswordStep.succeedsWith(passwordResult)
          _ <- env.router.advance(authId, record)
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
          preparePasswordTimes = env.otpConversationService.preparePasswordStep.times
        yield assertTrue(
          prepareOtpTimes == 0,
          preparePasswordTimes == 1,
        )
      },
      test("routes to set-password before passkey enrollment when a password change is required") {
        val env = Env()
        val flow = loginFlow.copy(
          primary = loginFlow.primary.copy(
            factors = List(AuthFactor(`type` = AuthFactorType.passkeyEnroll, required = true)),
          ),
        )
        val record = loginRecord.copy(authFlow = flow, needsPasswordChange = true)
        val expected = ConversationResult.RenderStep(
          ConversationStep.SetPassword(
            factorIndex = flow.primary.factors.length,
            timesSubmitted = 0,
            rateLimitExceeded = false,
            passwordReused = false,
          ),
        )
        for
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.offerSetPassword.succeedsWith(expected)
          _ <- env.router.advance(authId, record)
          calls = env.otpConversationService.offerSetPassword.calls
        yield assertTrue(calls.length == 1, calls.head._2 == record)
      },
      test("calls finish when all factors are already satisfied") {
        val env = Env()
        val now = Instant.now()
        val testCode = AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = SessionId(Array.fill(32)(2.toByte))
        val completeResult = ConversationResult.Complete(redirectUri, Some(State("test-state")), testCode, testSessionId, None, testUserAgentId, UserAgentData(None, testUserId, UserAgentDetails.parse(None)))
        val record = otpRecord.copy(
          amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))),
        )
        for
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.otpConversationService.finish.succeedsWith(completeResult)
          _ <- env.router.advance(authId, record)
          finishTimes = env.otpConversationService.finish.times
          prepareOtpTimes = env.otpConversationService.prepareInitialOtp.times
        yield assertTrue(
          finishTimes == 1,
          prepareOtpTimes == 0,
        )
      },
    ),

    suite("consent submissions")(
      test("routes an allow submission to allowConsent with the submitted scope") {
        val env = Env()
        val consentStep = ConversationStep.Consent(requestedScope = Set(ScopeToken.OpenId), allowPartial = true)
        val record = otpRecord.copy(step = consentStep)
        val completeResult = ConversationResult.Complete(
          redirectUri,
          Some(State("test-state")),
          AuthorizationCode(Array.fill(32)(1.toByte)),
          SessionId(Array.fill(32)(2.toByte)),
          None,
          testUserAgentId,
          UserAgentData(None, testUserId, UserAgentDetails.parse(None)),
        )
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(record))
          _ <- env.otpConversationService.allowConsent.succeedsWith(completeResult)
          (result, _) <- env.router.submit(authId, ConsentAllowSubmission(Set(ScopeToken.OpenId), "test-csrf"), None, None)
          calls = env.otpConversationService.allowConsent.calls
        yield assertTrue(
          result == completeResult,
          calls.map(_._3) == List(consentStep),
          calls.map(_._4) == List(Set(ScopeToken.OpenId)),
        )
      },
      test("routes a deny submission to accessDenied") {
        val env = Env()
        val record = otpRecord.copy(
          step = ConversationStep.Consent(requestedScope = Set(ScopeToken.OpenId), allowPartial = false),
        )
        val accessDeniedResult = ConversationResult.RenderStep(ConversationStep.AccessDenied)
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(record))
          _ <- env.otpConversationService.accessDenied.succeedsWith(accessDeniedResult)
          (result, _) <- env.router.submit(authId, ConsentDenySubmission("test-csrf"), None, None)
          allowTimes = env.otpConversationService.allowConsent.times
        yield assertTrue(result == accessDeniedResult, allowTimes == 0)
      },
      test("rejects a consent submission when the conversation is on another step") {
        val env = Env()
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(otpRecord))
          (result, _) <- env.router.submit(authId, ConsentAllowSubmission(Set(ScopeToken.OpenId), "test-csrf"), None, None)
          allowTimes = env.otpConversationService.allowConsent.times
        yield assertTrue(result == ConversationResult.BadRequest, allowTimes == 0)
      },
      test("rejects a consent submission carrying the wrong csrf token") {
        val env = Env()
        val record = otpRecord.copy(
          step = ConversationStep.Consent(requestedScope = Set(ScopeToken.OpenId), allowPartial = false),
        )
        for
          _ <- env.otpConversationService.find.succeedsWith(Some(record))
          exit <- env.router.submit(authId, ConsentAllowSubmission(Set(ScopeToken.OpenId), "wrong"), None, None).exit
          allowTimes = env.otpConversationService.allowConsent.times
        yield assertTrue(exit == Exit.fail(Error.BadRequest), allowTimes == 0)
      },
    ),
  )

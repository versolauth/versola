package versola.oauth.conversation

import versola.auth.TestEnvConfig
import versola.oauth.challenge.passkey.{PasskeyRepository, WebAuthnService}
import versola.auth.model.OtpCode
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFlow, AuthMethodRef, ClientId, PassedAuthFactor, PassedFactorRecord, PrimaryCredential, ScopeToken}
import versola.oauth.client.model.{AuthFlow, ClientId, PrimaryCredential, ScopeToken}
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.conversation.otp.model.SubmitOtpResult
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.SessionRepository
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.oauth.userinfo.model.UserInfoResponse
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.{AuthPropertyGenerator, CoreConfig, Email, EnvName, Secret, SecureRandom, SecurityService, UnitSpecBase}
import zio.ZIO
import zio.http.URL
import zio.json.ast
import zio.test.*

import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

object OtpConversationServiceSpec extends UnitSpecBase:

  val email = Email("test@example.com")
  val authId = AuthId(UUID.randomUUID())
  val userId = UserId(UUID.randomUUID())
  val otpCode = OtpCode("123456")
  val realOtp = ConversationStep.Otp(
    real = Some(ConversationStep.Otp.Real(otpCode)),
    timesRequested = 0,
    timesSubmitted = 0,
    factorIndex = 0,
    rateLimitExceeded = false,
    lockedSeconds = 0,
    lastSentAt = None,
  )

  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val scope = Set(ScopeToken("openid"), ScopeToken("profile"))
  val codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  val codeChallengeMethod = CodeChallengeMethod.S256

  class Env:
    val otpService = stub[OtpService]
    val passwordService = stub[PasswordService]
    val conversationRepository = stub[ConversationRepository]
    val userRepository = stub[UserRepository]
    val authorizationCodeRepository = stub[AuthorizationCodeRepository]
    val sessionRepository = stub[SessionRepository]
    val authPropertyGenerator = stub[AuthPropertyGenerator]
    val securityService = stub[SecurityService]
    val userInfoService = stub[versola.oauth.userinfo.UserInfoService]
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

  val initialConversation = ConversationRecord(
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
  )

  val otpRecord = initialConversation.copy(
    userId = Some(userId),
    credential = Some(Left(email)),
    userEmail = Some(email),
  )
  val submittedOtp = realOtp.copy(timesRequested = 1)

  val spec = suite("OtpConversationService")(
    suite("prepareInitialOtp")(
      test("create conversation record and send OTP") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.userRepository.findByCredential.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
          resultSentAt = result match
            case ConversationResult.RenderStep(otp: ConversationStep.Otp) => otp.lastSentAt
            case _ => None
          persistedSentAt = env.conversationRepository.overwrite.calls.last._2.step match
            case otp: ConversationStep.Otp => otp.lastSentAt
            case _ => None
        yield assertTrue(
          resultSentAt.isDefined,
          persistedSentAt.isDefined,
          env.conversationRepository.overwrite.calls.length == 1,
        )
      },
      test("cache user information when user exists") {
        val env = Env()
        val userEmail = Email("user@example.com")
        val userPhone = versola.util.Phone("+1234567890")
        val userLogin = versola.user.model.Login("testuser")
        val userClaims = ast.Json.Obj("name" -> ast.Json.Str("Test User"))
        val user = UserRecord(
          id = userId,
          email = Some(userEmail),
          phone = Some(userPhone),
          login = Some(userLogin),
          claims = userClaims,
          uiLocales = None,
        )
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.userRepository.findByCredential.succeedsWith(Some(user))
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
          overwriteCalls = env.conversationRepository.overwrite.calls
        yield assertTrue(
          result.isInstanceOf[ConversationResult.RenderStep],
          overwriteCalls.length == 1,
          overwriteCalls.head._2.userEmail.contains(userEmail),
          overwriteCalls.head._2.userPhone.contains(userPhone),
          overwriteCalls.head._2.userLogin.contains(userLogin),
          overwriteCalls.head._2.userClaims.contains(userClaims),
        )
      },
      test("not cache user information when user does not exist") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
          overwriteCalls = env.conversationRepository.overwrite.calls
        yield assertTrue(
          result.isInstanceOf[ConversationResult.RenderStep],
          overwriteCalls.length == 1,
          overwriteCalls.head._2.userEmail.isEmpty,
          overwriteCalls.head._2.userPhone.isEmpty,
          overwriteCalls.head._2.userLogin.isEmpty,
          overwriteCalls.head._2.userClaims.isEmpty,
        )
      },
      test("return AccessDenied when banned on OTP request") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("return AccessDenied when rate limited on OTP request") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("return AccessDenied when banned on OTP submit (ban earned via wrong codes)") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.returnsZIO:
            case (_, _, types) if types.contains(ChallengeType.OtpSubmit) => ZIO.succeed(LimitStatus.Banned)
            case _ => ZIO.succeed(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("return IllegalState when overwrite fails due to optimistic concurrency conflict") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.IllegalState)
      },
    ),
    suite("checkOtp")(
      test("return StepPassed when OTP is correct") {
        val env = Env()
        val otp = realOtp.copy(timesRequested = 1)
        val record = ConversationRecord(
          clientId = clientId,
          redirectUri = redirectUri,
          scope = scope,
          codeChallenge = codeChallenge,
          codeChallengeMethod = codeChallengeMethod,
          state = None,
          userId = Some(userId),
          credential = Some(Left(email)),
          step = otp,
          requestedClaims = None,
          uiLocales = None,
          nonce = None,
          responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
          userEmail = Some(email),
          userPhone = None,
          userLogin = None,
          userClaims = Some(zio.json.ast.Json.Obj()),
          authFlow = AuthFlow.default,
          userAgent = None,
          version = 0,
          amr = Map.empty,
        )
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(record, otp, otpCode, authId)
        yield assertTrue(result.isInstanceOf[ConversationResult.StepPassed])
      },
      test("return AccessDenied when subject is banned") {
        val env = Env()
        val otp = realOtp.copy(timesRequested = 1, timesSubmitted = 4)
        val record = ConversationRecord(
          clientId = clientId,
          redirectUri = redirectUri,
          scope = scope,
          codeChallenge = codeChallenge,
          codeChallengeMethod = codeChallengeMethod,
          state = None,
          userId = Some(userId),
          credential = Some(Left(email)),
          step = otp,
          requestedClaims = None,
          uiLocales = None,
          nonce = None,
          responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
          userEmail = Some(email),
          userPhone = None,
          userLogin = None,
          userClaims = Some(zio.json.ast.Json.Obj()),
          authFlow = AuthFlow.default,
          userAgent = None,
          version = 0,
          amr = Map.empty,
        )
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(record, otp, otpCode, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("re-render step with rate limit flag when rate limited") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
        yield assertTrue(result == ConversationResult.RenderStep(submittedOtp.copy(rateLimitExceeded = true, lockedSeconds = 30)))
      },
      test("re-render incremented step on wrong code when still allowed") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            submittedOtp.copy(timesSubmitted = 1, rateLimitExceeded = false),
          ),
        )
      },
      test("re-render step with rate limit flag on wrong code when rate limited") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            submittedOtp.copy(timesSubmitted = 1, rateLimitExceeded = true, lockedSeconds = 30),
          ),
        )
      },
      test("return AccessDenied on wrong code when ban is applied") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
    ),
    suite("finish")(
      test("generate ID token when openid scope is present and response_type includes id_token") {
        val env = Env()
        val userEmail = Email("user@example.com")
        val userClaims = ast.Json.Obj("name" -> ast.Json.Str("Test User"))
        val nonce = versola.oauth.model.Nonce("test-nonce")
        val conversation = initialConversation.copy(
          userId = Some(userId),
          scope = Set(ScopeToken.OpenId, ScopeToken("profile")),
          responseType = zio.prelude.NonEmptySet(
            versola.oauth.authorize.model.ResponseTypeEntry.Code,
            versola.oauth.authorize.model.ResponseTypeEntry.IdToken,
          ),
          nonce = Some(nonce),
          userEmail = Some(userEmail),
          userClaims = Some(userClaims),
          amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(java.time.Instant.EPOCH, Set(AuthMethodRef.otp))),
        )
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testSessionIdMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(4.toByte))
        val testCodeMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(5.toByte))

        for
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.securityService.mac.returnsZIOOnCall:
            case 1 => ZIO.succeed(testSessionIdMac)
            case 2 => ZIO.succeed(testCodeMac)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.userInfoService.getUserInfoForIdToken.succeedsWith(
            UserInfoResponse(
              claims = Map("sub" -> ast.Json.Str(userId.toString), "email" -> ast.Json.Str(userEmail)),
            )
          )
          result <- env.service.finish(authId, conversation)
        yield result match
          case complete: ConversationResult.Complete =>
            assertTrue(
              complete.idTokenData.isDefined,
              complete.idTokenData.get.claims.contains("sub"),
              complete.idTokenData.get.claims.contains("email"),
              complete.idTokenData.get.claims.contains("amr"),
              complete.idTokenData.get.claims.contains("auth_time"),
              complete.idTokenData.get.clientId == clientId,
            )

          case _ =>
            assertTrue(false)
      },
      test("not generate ID token when openid scope is missing") {
        val env = Env()
        val conversation = initialConversation.copy(
          userId = Some(userId),
          scope = Set(ScopeToken("profile")),
          responseType = zio.prelude.NonEmptySet(
            versola.oauth.authorize.model.ResponseTypeEntry.Code,
            versola.oauth.authorize.model.ResponseTypeEntry.IdToken,
          ),
        )
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testSessionIdMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(4.toByte))
        val testCodeMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(5.toByte))

        for
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.securityService.mac.returnsZIOOnCall:
            case 1 => ZIO.succeed(testSessionIdMac)
            case 2 => ZIO.succeed(testCodeMac)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          result <- env.service.finish(authId, conversation)
        yield result match
          case complete: ConversationResult.Complete => assertTrue(complete.idTokenData.isEmpty)
          case _ => assertTrue(false)
      },
      test("not generate ID token when response_type does not include id_token") {
        val env = Env()
        val conversation = initialConversation.copy(
          userId = Some(userId),
          scope = Set(ScopeToken.OpenId, ScopeToken("profile")),
          responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
        )
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testSessionIdMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(4.toByte))
        val testCodeMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(5.toByte))

        for
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.securityService.mac.returnsZIOOnCall:
            case 1 => ZIO.succeed(testSessionIdMac)
            case 2 => ZIO.succeed(testCodeMac)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          result <- env.service.finish(authId, conversation)
        yield result match
          case complete: ConversationResult.Complete => assertTrue(complete.idTokenData.isEmpty)
          case _ => assertTrue(false)
      },
      test("deny access when expectedUserId does not match authenticated userId") {
        val env = Env()
        val differentUserId = UserId(UUID.randomUUID())
        val conversation = initialConversation.copy(
          userId = Some(userId),
          expectedUserId = Some(differentUserId),
        )
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finish(authId, conversation)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
    ),
  )

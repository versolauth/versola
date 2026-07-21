package versola.oauth.conversation

import versola.auth.TestEnvConfig
import versola.auth.model.{CredentialId, CredentialDeviceType, OtpCode, PasskeyRecord, Password}
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.challenge.password.model.CheckPassword
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.conversation.otp.model.SubmitOtpResult
import versola.oauth.model.*
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.*
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.user.model.*
import versola.util.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

object ConversationServiceSpec extends UnitSpecBase:

  private val authId = AuthId(UUID.randomUUID())
  private val tenantId = TenantId("tenant-1")
  private val clientId = ClientId("client-1")
  private val userId = UserId(UUID.randomUUID())
  private val email = Email("test@example.com")
  private val redirectUri = zio.http.URL.decode("https://example.com/callback").toOption.get

  private val userRecord = UserRecord(
    id = userId,
    email = Some(email),
    phone = None,
    login = Some(Login("testuser")),
    claims = Json.Obj("name" -> Json.Str("Test User")),
    uiLocales = None
  )

  private val conversationRecord = ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = Set(ScopeToken("read")),
    codeChallenge = CodeChallenge("a" * 43),
    codeChallengeMethod = CodeChallengeMethod.S256,
    state = Some(State("test-state")),
    userId = None,
    credential = None,
    step = ConversationStep.Credential(List(PrimaryCredential.email), true, false),
    requestedClaims = None,
    uiLocales = Some(List("en")),
    nonce = None,
    responseType = zio.prelude.NonEmptySet(ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = AuthFlow.default,
    userAgent = None,
    version = 1,
    amr = Map.empty,
    needsPasswordChange = false,
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
    val webAuthnService = stub[versola.oauth.challenge.passkey.WebAuthnService]
    val passkeyRepository = stub[PasskeyRepository]
    val configService = stub[OAuthConfigurationService]

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
      TestEnvConfig.coreConfig,
      submissionLimiter,
      webAuthnService,
      passkeyRepository,
      configService
    )

  def spec = suite("ConversationService")(
    suite("prepareInitialOtp")(
      test("prepares and sends OTP when allowed") {
        val env = Env()
        val now = Instant.parse("2026-07-13T10:00:00Z")
        val otpStep = ConversationStep.Otp(None, 1, 0, 0, false, 0, Some(now))

        for
          _ <- TestClock.setTime(now)
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.userRepository.findByCredential.succeedsWith(Some(userRecord))
          _ <- env.otpService.prepareOtp.succeedsWith(otpStep)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          result <- env.service.prepareInitialOtp(authId, conversationRecord, Left(email), 0)
        yield
          assertTrue(result == ConversationResult.RenderStep(otpStep))
      },
      test("denies access when rate limited") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, conversationRecord, Left(email), 0)
        yield
          assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      }
    ),
    suite("checkOtp")(
      test("returns StepPassed on successful OTP check") {
        val env = Env()
        val now = Instant.parse("2026-07-13T10:00:00Z")
        val otpStep = ConversationStep.Otp(None, 1, 0, 0, false, 0, Some(now))
        val record = conversationRecord.copy(credential = Some(Left(email)))

        for
          _ <- TestClock.setTime(now)
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(record, otpStep, OtpCode("123456"), authId)
        yield
          result match
            case ConversationResult.StepPassed(updated) =>
              assertTrue(updated.amr.contains(PassedAuthFactor.otp))
            case _ => assertTrue(false)
      },
      test("records limit and re-renders on OTP failure") {
        val env = Env()
        val otpStep = ConversationStep.Otp(None, 1, 0, 0, false, 0, None)
        val record = conversationRecord.copy(credential = Some(Left(email)))

        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(record, otpStep, OtpCode("wrong"), authId)
        yield
          result match
            case ConversationResult.RenderStep(step: ConversationStep.Otp) =>
              assertTrue(step.timesSubmitted == 1)
            case _ => assertTrue(false)
      }
    ),
    suite("checkPassword")(
      test("returns StepPassed on successful password check") {
        val env = Env()
        val now = Instant.parse("2026-07-13T10:00:00Z")
        val passStep = ConversationStep.Password(0, None, 0, false)
        val record = conversationRecord.copy(userId = Some(userId))

        for
          _ <- TestClock.setTime(now)
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPassword(record, passStep, Password("secret"), authId)
        yield
          result match
            case ConversationResult.StepPassed(updated) =>
              assertTrue(updated.amr.contains(PassedAuthFactor.password))
            case _ => assertTrue(false)
      }
    ),
    suite("finish")(
      test("completes conversation and creates session/code") {
        val env = Env()
        val now = Instant.parse("2026-07-13T10:00:00Z")
        val record = conversationRecord.copy(userId = Some(userId))
        val code = AuthorizationCode.fromString("code")
        val sessionId = SessionId.fromString("session")
        val accessToken = AccessToken.fromString("token")
        val mac = MAC(Array.fill(32)(1.toByte))

        for
          _ <- TestClock.setTime(now)
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(code)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(sessionId)
          _ <- env.securityService.mac.succeedsWith(mac)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
          _ <- env.configService.getSessionTtl.succeedsWith(1.hour)
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Some(30.minutes))
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          result <- env.service.finish(authId, record)
        yield
          result match
            case ConversationResult.Complete(uri, _, c, s, _) =>
              assertTrue(uri == redirectUri) &&
              assertTrue(c == code) &&
              assertTrue(s == sessionId)
            case _ => assertTrue(false)
      }
    ),

    suite("checkLoginPassword")(
      test("authenticates login+password and returns StepPassed") {
        val env = Env()
        val now = Instant.parse("2026-07-13T10:00:00Z")

        for
          _ <- TestClock.setTime(now)
          _ <- env.userRepository.findByLogin.succeedsWith(Some(userRecord))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.passwordService.verifyPassword.succeedsWith(CheckPassword.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkLoginPassword(authId, conversationRecord, Login("testuser"), Password("secret"))
        yield
          result match
            case ConversationResult.StepPassed(updated) =>
              assertTrue(updated.userId.contains(userId)) &&
              assertTrue(updated.amr.contains(PassedAuthFactor.password))
            case _ => assertTrue(false)
      }
    ),

    suite("offerPasskeyEnroll")(
      test("starts registration if user has no passkeys") {
        val env = Env()
        val record = conversationRecord.copy(userId = Some(userId))
        val passkeySettings = PasskeySettings("rpId", "rpName", List("origin"), "required")
        val ceremony = versola.oauth.challenge.passkey.PasskeyCeremony("request", "options")

        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.webAuthnService.startRegistration.succeedsWith(ceremony)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.offerPasskeyEnroll(authId, record)
        yield
          result match
            case ConversationResult.RenderStep(ConversationStep.PasskeyEnroll(req, opt, _)) =>
              assertTrue(req == "request") && assertTrue(opt == "options")
            case _ => assertTrue(false)
      },
      test("skips enrollment if user already has passkeys") {
        val env = Env()
        val record = conversationRecord.copy(userId = Some(userId))
        val passkeySettings = PasskeySettings("rpId", "rpName", List("origin"), "required")
        val code = AuthorizationCode.fromString("code")
        val sessionId = SessionId.fromString("session")
        val accessToken = AccessToken.fromString("token")
        val mac = MAC(Array.fill(32)(1.toByte))

        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(PasskeyRecord(
            id = CredentialId(Array.fill(16)(1.toByte)),
            userId = userId,
            publicKey = Array.empty,
            signatureCounter = 0L,
            deviceType = CredentialDeviceType.SingleDevice,
            backedUp = false,
            backupEligible = false,
            transports = Nil,
            attestationObject = None,
            clientDataJson = None,
            aaguid = None,
            name = None,
            lastUsedAt = None,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
          )))
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(code)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(sessionId)
          _ <- env.securityService.mac.succeedsWith(mac)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
          _ <- env.configService.getSessionTtl.succeedsWith(1.hour)
          _ <- env.configService.getSessionIdleTtl.succeedsWith(None)
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          result <- env.service.offerPasskeyEnroll(authId, record)
        yield
          assertTrue(result.isInstanceOf[ConversationResult.Complete])
      }
    )

  )

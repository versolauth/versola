package versola.oauth.conversation

import versola.antifraud.{AntifraudAction, AntifraudOutcome, RecordingAntifraudEvents}
import versola.auth.TestEnvConfig
import versola.auth.model.OtpCode
import versola.oauth.authorize.AcrResolutionService
import versola.oauth.challenge.passkey.{PasskeyEnrollmentRepository, PasskeyRepository, WebAuthnService}
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFlow, AuthMethodRef, ClientId, PassedAuthFactor, PassedFactorRecord, PrimaryCredential, ScopeToken}
import versola.oauth.client.model.{AuthFlow, ClientId, PrimaryCredential, ScopeToken}
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.conversation.otp.model.SubmitOtpResult
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.model.RefreshTokenFamilyId
import versola.oauth.session.{SessionRepository, UserAgentRepository}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.oauth.userinfo.model.UserInfoResponse
import versola.user.model.{UserId, UserRecord}
import versola.user.{UserRepository, UserService}
import versola.util.{AuthPropertyGenerator, CoreConfig, Email, EnvName, Secret, SecureRandom, SecurityService, UnitSpecBase}
import zio.http.URL
import zio.json.ast
import zio.test.*
import zio.{Ref, ZIO}

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

  class Env(smsAllowed: Boolean = true, afterRequested: zio.UIO[Unit] = ZIO.unit):
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
    val passkeyEnrollmentRepository = stub[PasskeyEnrollmentRepository]
    val configService = stub[OAuthConfigurationService]
    val acrResolver = stub[AcrResolutionService]
    val config = TestEnvConfig.coreConfig
    val userAgentRepository = stub[UserAgentRepository]
    val secureRandom = stub[SecureRandom]
    val userService = stub[UserService]
    val consentService = stub[versola.oauth.consent.ConsentService]
    val antifraud = RecordingAntifraudEvents(smsAllowed, afterRequested)
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
      passkeyEnrollmentRepository,
      configService,
      acrResolver,
      userAgentRepository,
      secureRandom,
      userService,
      consentService,
      antifraud,
    )

  val initialConversation = ConversationRecord(
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

  val otpRecord = initialConversation.copy(
    userId = Some(userId),
    credential = Some(Left(email)),
    userEmail = Some(email),
  )
  val submittedOtp = realOtp.copy(timesRequested = 1)

  val spec = suite("OtpConversationService")(
    suite("antifraud events")(
      test("a request that sent a code is recorded as sent, with the destination, client and user agent") {
        val env = Env()
        val conversation = initialConversation.copy(userAgent = Some("Mozilla/5.0"))
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          _ <- env.service.prepareInitialOtp(authId, conversation, Left(email), factorIndex = 0)
          steps = env.antifraud.steps
        yield assertTrue(
          steps == List(
            RecordingAntifraudEvents.Step(AntifraudAction.otp_request, authId, clientId, Some("Mozilla/5.0"), Left(email), AntifraudOutcome.sent),
          ),
        )
      },
      test("a request that sent nothing because the destination is not a user is recorded as suppressed") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp.copy(real = None))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
          outcomes = env.antifraud.steps.map(_.outcome)
        yield assertTrue(outcomes == List(AntifraudOutcome.suppressed))
      },
      test("a request refused by a ban, a rate limit or a submit ban is recorded as throttled") {
        for
          banned <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
              _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Banned)
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
            yield env.antifraud.steps.map(_.outcome)
          }
          limited <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
              _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.RateLimited(30L))
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
            yield env.antifraud.steps.map(_.outcome)
          }
          submitBan <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Banned)
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
            yield env.antifraud.steps.map(_.outcome)
          }
        yield assertTrue(
          banned == List(AntifraudOutcome.throttled),
          limited == List(AntifraudOutcome.throttled),
          submitBan == List(AntifraudOutcome.throttled),
        )
      },
      test("a provider that fails is recorded as provider_error, and the failure still reaches the caller") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.failsWith(RuntimeException("OTP provider responded with 503"))
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0).exit
          outcomes = env.antifraud.steps.map(_.outcome)
        yield assertTrue(result.isFailure, outcomes == List(AntifraudOutcome.provider_error))
      },
      test("a closed range is answered the same for a real code and for the stand-in of an unknown user, and only the real one is charged") {
        def refused(otp: ConversationStep.Otp) =
          val env = Env(smsAllowed = false)
          for
            _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
            _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
            _ <- env.otpService.prepareOtp.succeedsWith(otp)
            _ <- env.conversationRepository.overwrite.succeedsWith(true)
            result <- env.service.prepareInitialOtp(authId, initialConversation, Right(versola.util.Phone("+4915123456789")), factorIndex = 0)
          yield (result, env.otpService.sendOtp.calls.isEmpty, env.antifraud.steps.map(_.outcome), env.antifraud.smsAsked)
        for
          real <- refused(realOtp)
          fake <- refused(realOtp.copy(real = None))
        yield assertTrue(
          real._1 == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          fake._1 == real._1,
          real._2,
          fake._2,
          real._3 == List(AntifraudOutcome.throttled),
          fake._3 == real._3,
          real._4 == List(true),
          fake._4 == List(false),
        )
      },
      test("a code that was charged and not sent is given back, whether the provider failed or the write was lost") {
        val failing = Env()
        val lost = Env()
        for
          _ <- failing.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- failing.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- failing.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- failing.conversationRepository.overwrite.succeedsWith(true)
          _ <- failing.otpService.sendOtp.failsWith(RuntimeException("503"))
          _ <- failing.service.prepareInitialOtp(authId, initialConversation, Right(versola.util.Phone("+4915123456789")), factorIndex = 0).exit
          _ <- lost.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- lost.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- lost.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- lost.conversationRepository.overwrite.succeedsWith(false)
          _ <- lost.service.prepareInitialOtp(authId, initialConversation, Right(versola.util.Phone("+4915123456789")), factorIndex = 0)
        yield assertTrue(failing.antifraud.smsReleased == 1, lost.antifraud.smsReleased == 1)
      },
      test("a code that was charged is given back when the write fails or the request is interrupted, not only on the expected branches") {
        val broken = Env()
        val interrupted = Env()
        for
          _ <- broken.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- broken.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- broken.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- broken.conversationRepository.overwrite.failsWith(RuntimeException("database is down"))
          _ <- broken.service.prepareInitialOtp(authId, initialConversation, Right(versola.util.Phone("+4915123456789")), factorIndex = 0).exit
          _ <- interrupted.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- interrupted.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- interrupted.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- interrupted.conversationRepository.overwrite.returnsZIO(_ => ZIO.never)
          fiber <- interrupted.service
            .prepareInitialOtp(authId, initialConversation, Right(versola.util.Phone("+4915123456789")), factorIndex = 0)
            .fork
          _ <- Live.live(ZIO.sleep(zio.Duration.fromMillis(200)))
          _ <- fiber.interrupt
        yield assertTrue(broken.antifraud.smsReleased == 1, interrupted.antifraud.smsReleased == 1)
      },
      test("an interruption after the send has succeeded does not give the code back, one before it has finished does") {
        val phone = Right(versola.util.Phone("+4915123456789"))
        def interruptedAt(env: Env) =
          for
            _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
            _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
            _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
            _ <- env.conversationRepository.overwrite.succeedsWith(true)
            fiber <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0).fork
            _ <- Live.live(ZIO.sleep(zio.Duration.fromMillis(200)))
            _ <- fiber.interrupt
          yield env.antifraud.smsReleased
        val duringSend = Env()
        val afterSend = Env(afterRequested = ZIO.never)
        for
          _ <- duringSend.otpService.sendOtp.returnsZIO(_ => ZIO.never)
          _ <- afterSend.otpService.sendOtp.succeedsWith(())
          before <- interruptedAt(duringSend)
          after <- interruptedAt(afterSend)
        yield assertTrue(before == 1, after == 0, afterSend.otpService.sendOtp.calls.size == 1)
      },
      test("an address never asks the budget") {
        val env = Env(smsAllowed = false)
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(env.antifraud.smsAsked.isEmpty, env.antifraud.smsReleased == 0)
      },
      test("a request that lost the write race and sent nothing records nothing") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
          steps = env.antifraud.steps
        yield assertTrue(steps.isEmpty)
      },
      test("a correct code whose step could not be stored records nothing, so a retry cannot count a second verification") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          _ <- env.userRepository.markVerified.succeedsWith(Option.empty[ast.Json.Obj])
          result <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
          steps = env.antifraud.steps
        yield assertTrue(result == ConversationResult.WriteConflict, steps.isEmpty)
      },
      test("a correct code is recorded as verified, a wrong one as wrong_code, and a locked destination as locked") {
        for
          verified <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
              _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.userRepository.markVerified.succeedsWith(Option.empty[ast.Json.Obj])
              _ <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
            yield env.antifraud.steps
          }
          wrong <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
              _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
              _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
            yield env.antifraud.steps.map(_.outcome)
          }
          banned <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Banned)
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
            yield env.antifraud.steps.map(_.outcome)
          }
          limited <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.RateLimited(30L))
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
            yield env.antifraud.steps.map(_.outcome)
          }
        yield assertTrue(
          verified.map(_.outcome) == List(AntifraudOutcome.verified),
          verified.head.action == AntifraudAction.otp_verify,
          wrong == List(AntifraudOutcome.wrong_code),
          banned == List(AntifraudOutcome.locked),
          limited == List(AntifraudOutcome.locked),
        )
      },
    ),
    suite("prepareInitialOtp")(
      test("create conversation record and send OTP") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
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
        val resolvedConversation = initialConversation.copy(
          userId = Some(user.id),
          userEmail = user.email,
          userPhone = user.phone,
          userLogin = user.login,
          userClaims = Some(user.claims),
        )
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, resolvedConversation, Left(email), factorIndex = 0)
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
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
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
      test("claim the request against the limiter before any OTP is sent") {
        val env = Env()
        // Both stubs append to one shared trace, so this asserts the actual ordering rather than
        // merely that each was called: the bug in #103 was precisely that the limiter ran too late,
        // and a test that only counted calls would still pass with the charge moved back after send.
        for
          trace <- Ref.make(List.empty[String])
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.returnsZIO: _ =>
            trace.update(_ :+ "tryAcquire").as(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.returnsZIO: _ =>
            trace.update(_ :+ "sendOtp")
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
          order <- trace.get
        yield assertTrue(
          order == List("tryAcquire", "sendOtp"),
          env.submissionLimiter.tryAcquire.calls.map(_._3) == List(ChallengeType.OtpRequest),
          // The old code recorded only after sending, leaving a window for concurrent requests to
          // each pass the check and each send an OTP.
          env.submissionLimiter.recordLimit.calls.isEmpty,
        )
      },
      test("return AccessDenied and send nothing when banned on OTP request") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("return AccessDenied and send nothing when rate limited on OTP request") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("return AccessDenied when banned on OTP submit (ban earned via wrong codes)") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.returnsZIO:
            case (_, _, types) if types.contains(ChallengeType.OtpSubmit) => ZIO.succeed(LimitStatus.Banned)
            case _ => ZIO.succeed(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          // A submit ban must not cost the subject a request attempt on top.
          env.submissionLimiter.tryAcquire.calls.isEmpty,
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("return WriteConflict when overwrite fails due to optimistic concurrency conflict") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0)
        yield assertTrue(result == ConversationResult.WriteConflict)
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
          codeChallenge = Some(codeChallenge),
          codeChallengeMethod = Some(codeChallengeMethod),
          state = None,
          userId = Some(userId),
          credential = Some(Left(email)),
          step = otp,
          requestedClaims = None,
          uiLocales = None,
          nonce = None,
          responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
          responseMode = versola.oauth.authorize.model.ResponseMode.Query,
          userEmail = Some(email),
          userPhone = None,
          userLogin = None,
          userClaims = Some(zio.json.ast.Json.Obj()),
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
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.userRepository.markVerified.succeedsWith(Option.empty[ast.Json.Obj])
          result <- env.service.checkOtp(record, otp, otpCode, authId)
        yield assertTrue(result.isInstanceOf[ConversationResult.StepPassed])
      },
      test("reports a write conflict when the conversation moved on") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- env.userRepository.markVerified.succeedsWith(Option.empty[ast.Json.Obj])
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
        yield assertTrue(
          result == ConversationResult.WriteConflict,
          // the flag records that the OTP passed; losing the write race does not unmake it
          env.userRepository.markVerified.calls == List((userId, Left(email))),
        )
      },
      test("return AccessDenied when subject is banned") {
        val env = Env()
        val otp = realOtp.copy(timesRequested = 1, timesSubmitted = 4)
        val record = ConversationRecord(
          clientId = clientId,
          redirectUri = redirectUri,
          scope = scope,
          codeChallenge = Some(codeChallenge),
          codeChallengeMethod = Some(codeChallengeMethod),
          state = None,
          userId = Some(userId),
          credential = Some(Left(email)),
          step = otp,
          requestedClaims = None,
          uiLocales = None,
          nonce = None,
          responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
          responseMode = versola.oauth.authorize.model.ResponseMode.Query,
          userEmail = Some(email),
          userPhone = None,
          userLogin = None,
          userClaims = Some(zio.json.ast.Json.Obj()),
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
      test("marks the credential as verified on OTP success and hands the fresh claims to the conversation") {
        val env = Env()
        val otp = realOtp.copy(timesRequested = 1)
        val fresh = ast.Json.Obj("email_verified" -> ast.Json.Bool(true))
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.userRepository.markVerified.succeedsWith(Some(fresh))
          result <- env.service.checkOtp(otpRecord, otp, otpCode, authId)
        yield assertTrue(
          env.userRepository.markVerified.calls == List((userId, Left(email))),
          // markVerified ran before the overwrite: the stored conversation already carries its claims
          env.conversationRepository.overwrite.calls.map(_._2.userClaims) == List(Some(fresh)),
          result.isInstanceOf[ConversationResult.StepPassed],
        )
      },
      test("keeps the conversation's claims when the stored contact no longer matches the credential") {
        val env = Env()
        val otp = realOtp.copy(timesRequested = 1)
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.userRepository.markVerified.succeedsWith(Option.empty[ast.Json.Obj])
          _ <- env.service.checkOtp(otpRecord, otp, otpCode, authId)
        yield assertTrue(env.conversationRepository.overwrite.calls.map(_._2.userClaims) == List(otpRecord.userClaims))
      },
      test("does not call markVerified when OTP fails") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
        yield assertTrue(env.userRepository.markVerified.calls.isEmpty)
      },
      test("does not call markVerified when userId is absent") {
        // Conversation where credential is set but no userId yet (anonymous registration flow)
        val env = Env()
        val anonymousRecord = otpRecord.copy(userId = None)
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkOtp(anonymousRecord, submittedOtp, otpCode, authId)
        yield assertTrue(env.userRepository.markVerified.calls.isEmpty)
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
          grantedScope = Some(Set(ScopeToken.OpenId, ScopeToken("profile"))),
          responseType = zio.prelude.NonEmptySet(
            versola.oauth.authorize.model.ResponseTypeEntry.Code,
            versola.oauth.authorize.model.ResponseTypeEntry.IdToken,
          ),
          responseMode = versola.oauth.authorize.model.ResponseMode.Fragment,
          nonce = Some(nonce),
          userEmail = Some(userEmail),
          userClaims = Some(userClaims),
          amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(java.time.Instant.EPOCH, Set(AuthMethodRef.otp))),
        )
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testPublicSessionId = versola.oauth.session.model.PublicSessionId("public-session")
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testSessionIdMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(4.toByte))
        val testCodeMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(5.toByte))

        for
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextPublicSessionId.succeedsWith(testPublicSessionId)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.authPropertyGenerator.nextRefreshTokenFamilyId.succeedsWith(RefreshTokenFamilyId("family-1"))
          _ <- env.securityService.mac.returnsZIOOnCall:
            case 1 => ZIO.succeed(testSessionIdMac)
            case 2 => ZIO.succeed(testCodeMac)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
          _ <- env.userInfoService.getUserInfoForIdToken.succeedsWith(
            UserInfoResponse(
              claims = Map("sub" -> ast.Json.Str(userId.toString), "email" -> ast.Json.Str(userEmail)),
            ),
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
          grantedScope = Some(Set(ScopeToken("profile"))),
          responseType = zio.prelude.NonEmptySet(
            versola.oauth.authorize.model.ResponseTypeEntry.Code,
            versola.oauth.authorize.model.ResponseTypeEntry.IdToken,
          ),
          responseMode = versola.oauth.authorize.model.ResponseMode.Fragment,
        )
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testPublicSessionId = versola.oauth.session.model.PublicSessionId("public-session")
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testSessionIdMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(4.toByte))
        val testCodeMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(5.toByte))

        for
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextPublicSessionId.succeedsWith(testPublicSessionId)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.authPropertyGenerator.nextRefreshTokenFamilyId.succeedsWith(RefreshTokenFamilyId("family-1"))
          _ <- env.securityService.mac.returnsZIOOnCall:
            case 1 => ZIO.succeed(testSessionIdMac)
            case 2 => ZIO.succeed(testCodeMac)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
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
          grantedScope = Some(Set(ScopeToken.OpenId, ScopeToken("profile"))),
          responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
          responseMode = versola.oauth.authorize.model.ResponseMode.Query,
        )
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testPublicSessionId = versola.oauth.session.model.PublicSessionId("public-session")
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testSessionIdMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(4.toByte))
        val testCodeMac: versola.util.MAC = versola.util.MAC(Array.fill(32)(5.toByte))

        for
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextPublicSessionId.succeedsWith(testPublicSessionId)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.authPropertyGenerator.nextRefreshTokenFamilyId.succeedsWith(RefreshTokenFamilyId("family-1"))
          _ <- env.securityService.mac.returnsZIOOnCall:
            case 1 => ZIO.succeed(testSessionIdMac)
            case 2 => ZIO.succeed(testCodeMac)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
          result <- env.service.finish(authId, conversation)
        yield result match
          case complete: ConversationResult.Complete => assertTrue(complete.idTokenData.isEmpty)
          case _ => assertTrue(false)
      },
    ),
  )

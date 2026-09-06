package versola.oauth.conversation

import versola.auth.TestEnvConfig
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyName, PasskeyRecord}
import versola.oauth.authorize.AcrResolutionService
import versola.oauth.challenge.passkey.{AssertionOutcome, PasskeyCeremony, PasskeyRepository, WebAuthnService}
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{
  AuthFlow,
  AuthMethodRef,
  ClientId,
  PassedAuthFactor,
  PasskeyAuthFlow,
  PasskeySettings,
  PrimaryCredential,
  ScopeToken,
}
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.{SessionRepository, UserAgentRepository}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.model.{UserId, UserRecord}
import versola.user.{UserRepository, UserService}
import versola.util.{AuthPropertyGenerator, SecureRandom, SecurityService, UnitSpecBase}
import zio.http.URL
import zio.test.*

import java.time.Instant
import java.util.UUID

object PasskeyConversationServiceSpec extends UnitSpecBase:

  val authId = AuthId(UUID.randomUUID())
  val userId = UserId(UUID.randomUUID())
  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val scope = Set(ScopeToken("openid"), ScopeToken("profile"))
  val codeChallenge = CodeChallenge("challenge")
  val codeChallengeMethod = CodeChallengeMethod.S256

  val passkeySettings = PasskeySettings(
    rpId = "localhost",
    rpName = "Versola",
    origins = List("http://localhost:3000"),
    userVerification = "preferred",
  )

  // Passkey login enabled at the client level, mirroring what AuthorizeEndpointService persists.
  val passkeyAuthFlow = AuthFlow.default.copy(passkey = Some(PasskeyAuthFlow(factors = Nil)))

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
      acrResolver,
      userAgentRepository,
      secureRandom,
      userService,
      consentService,
    )

  val credentialStep = ConversationStep.Credential(
    primaryCredentials = List(PrimaryCredential.email),
    inlinePassword = false,
    passkey = true,
    passkeyRequest = None,
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
    step = credentialStep,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = passkeyAuthFlow,
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

  // A minimal assertion response carrying a credential id, used as the throttle subject.
  val assertionResponse = """{"id":"cred-123"}"""

  def spec = suite("PasskeyConversationServiceSpec")(
    suite("startPasskeyAssertion")(
      test("return publicKeyOptions and update step with request") {
        val env = Env()
        val ceremony = PasskeyCeremony("req-state", "{}")
        for
          _ <- env.webAuthnService.startAssertion.succeedsWith(ceremony)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.startPasskeyAssertion(authId, baseRecord, credentialStep, passkeySettings)
        yield assertTrue(
          result == "{}",
          env.conversationRepository.overwrite.calls.head._2.step == credentialStep.copy(passkeyRequest = Some("req-state")),
        )
      },
      test("propagate a failure from the WebAuthn service") {
        val env = Env()
        for
          _ <- env.webAuthnService.startAssertion.failsWith(versola.oauth.challenge.passkey.WebAuthnError.PasskeysNotEnabled)
          exit <- env.service.startPasskeyAssertion(authId, baseRecord, credentialStep, passkeySettings).exit
        yield assertTrue(exit.isFailure, env.conversationRepository.overwrite.calls.isEmpty)
      },
    ),
    suite("finishPasskeyAssertion")(
      test("succeed and move to enrollment check, resetting the throttle counter") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        val outcome = AssertionOutcome(userId, CredentialId.fromString("id"), 1L)
        val user = UserRecord.empty(userId)
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.reset.succeedsWith(())
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.succeedsWith(outcome)
          _ <- env.userRepository.find.succeedsWith(Some(user))
          _ <- env.passkeyRepository.findByCredentialIdAndUser.succeedsWith(None)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.webAuthnService.startRegistration.succeedsWith(PasskeyCeremony("reg-req", "{}"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
          resetSubjects = env.submissionLimiter.reset.calls.map(_._2)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.PasskeyEnroll("reg-req", "{}")),
          resetSubjects == List("cred-123"),
        )
      },
      test("re-render credential step on assertion failure and record both subjects") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.failsWith(versola.oauth.challenge.passkey.WebAuthnError.CeremonyFailed("fail"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, Some("1.2.3.4"))
          recordedSubjects = env.submissionLimiter.recordLimit.calls.map(_._2).toSet
        yield assertTrue(
          result == ConversationResult.RenderStep(credentialStep.copy(passkeyRequest = None, passkeyFailed = true)),
          recordedSubjects == Set("ip:1.2.3.4", "cred-123"),
        )
      },
      test("re-render credential step on clone detection") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.failsWith(versola.oauth.challenge.passkey.WebAuthnError.AssertionFailed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
        yield assertTrue(
          result == ConversationResult.RenderStep(credentialStep.copy(passkeyRequest = None, passkeyFailed = true)),
        )
      },
      test("re-render credential step when the credential is not found") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.failsWith(versola.oauth.challenge.passkey.WebAuthnError.CredentialNotFound)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
        yield assertTrue(
          result == ConversationResult.RenderStep(credentialStep.copy(passkeyRequest = None, passkeyFailed = true)),
        )
      },
      test("return AccessDenied immediately when a subject is already banned") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, Some("1.2.3.4"))
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.webAuthnService.finishAssertion.calls.isEmpty,
        )
      },
      test("throttle by IP so a rotated credential id cannot dodge an existing ban") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("rotated-999"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, """{"id":"rotated-999"}""", Some("1.2.3.4"))
          banSubjects = env.submissionLimiter.statusForSubjects.calls.head._2
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          banSubjects == List("ip:1.2.3.4", "rotated-999"),
          env.webAuthnService.finishAssertion.calls.isEmpty,
        )
      },
      test("return AccessDenied immediately when the subject is rate-limited") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
          banSubjects = env.submissionLimiter.statusForSubjects.calls.head._2
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          banSubjects == List("cred-123"),
          env.webAuthnService.finishAssertion.calls.isEmpty,
        )
      },
      test("re-render without recording when the response has no credential id and no IP is available") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(None)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, """{"foo":"bar"}""", None)
        yield assertTrue(
          result == ConversationResult.RenderStep(credentialStep.copy(passkeyRequest = None, passkeyFailed = true)),
          env.submissionLimiter.statusForSubjects.calls.isEmpty,
          env.submissionLimiter.recordLimit.calls.isEmpty,
        )
      },
      test("throttle the IP and clear the request when the response has no credential id") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(None)
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, """{"foo":"bar"}""", Some("1.2.3.4"))
          banSubjects = env.submissionLimiter.statusForSubjects.calls.head._2
          recordedSubjects = env.submissionLimiter.recordLimit.calls.map(_._2)
        yield assertTrue(
          result == ConversationResult.RenderStep(credentialStep.copy(passkeyRequest = None, passkeyFailed = true)),
          banSubjects == List("ip:1.2.3.4"),
          recordedSubjects == List("ip:1.2.3.4"),
          env.webAuthnService.finishAssertion.calls.isEmpty,
        )
      },
      test("throttle the IP and clear the request when the credential id is empty") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(None)
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, """{"id":""}""", Some("1.2.3.4"))
          recordedSubjects = env.submissionLimiter.recordLimit.calls.map(_._2)
        yield assertTrue(
          result == ConversationResult.RenderStep(credentialStep.copy(passkeyRequest = None, passkeyFailed = true)),
          recordedSubjects == List("ip:1.2.3.4"),
          env.webAuthnService.finishAssertion.calls.isEmpty,
        )
      },
      test("deny access for a malformed payload when the IP subject is already banned") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(None)
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, """{"foo":"bar"}""", Some("1.2.3.4"))
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.submissionLimiter.recordLimit.calls.isEmpty,
          env.webAuthnService.finishAssertion.calls.isEmpty,
        )
      },
      test("return BadRequest when passkey login is not enabled for the client") {
        val env = Env()
        val record = baseRecord.copy(
          authFlow = AuthFlow.default,
          step = credentialStep.copy(passkey = false, passkeyRequest = Some("req-state")),
        )
        for
          result <- env.service.finishPasskeyAssertion(authId, record, "response-json", None)
        yield assertTrue(result == ConversationResult.BadRequest)
      },
      test("return BadRequest when no ceremony is pending (duplicate or replayed submission)") {
        val env = Env()
        for
          result <- env.service.finishPasskeyAssertion(authId, baseRecord, assertionResponse, None)
        yield assertTrue(result == ConversationResult.BadRequest, env.webAuthnService.credentialIdFromResponse.calls.isEmpty)
      },
      test("deny access when passkey settings vanish mid-ceremony") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.configService.getPasskeySettings.succeedsWith(None)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.webAuthnService.finishAssertion.calls.isEmpty,
        )
      },
      test("deny access when the resolved user no longer exists") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        val outcome = AssertionOutcome(userId, CredentialId.fromString("id"), 1L)
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.reset.succeedsWith(())
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.succeedsWith(outcome)
          _ <- env.userRepository.find.succeedsWith(None)
          _ <- env.passkeyRepository.findByCredentialIdAndUser.succeedsWith(None)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("deny access when the assertion resolves to a different user than the conversation already verified") {
        val env = Env()
        val otherUserId = UserId(UUID.randomUUID())
        val recordWithRequest = baseRecord.copy(
          userId = Some(otherUserId),
          step = credentialStep.copy(passkeyRequest = Some("req-state")),
        )
        val outcome = AssertionOutcome(userId, CredentialId.fromString("id"), 1L)
        val user = UserRecord.empty(userId)
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.reset.succeedsWith(())
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.succeedsWith(outcome)
          _ <- env.userRepository.find.succeedsWith(Some(user))
          _ <- env.passkeyRepository.findByCredentialIdAndUser.succeedsWith(None)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("records swk for a multi-device passkey and reports a write conflict when persistence fails") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        val outcome = AssertionOutcome(userId, CredentialId.fromString("id"), 1L)
        val user = UserRecord.empty(userId)
        val existingPasskey = PasskeyRecord(
          id = CredentialId.fromString("id"),
          userId = userId,
          publicKey = Array.empty,
          signatureCounter = 1L,
          deviceType = CredentialDeviceType.MultiDevice,
          backedUp = true,
          backupEligible = true,
          transports = Nil,
          attestationObject = None,
          clientDataJson = None,
          aaguid = None,
          name = None,
          lastUsedAt = None,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.reset.succeedsWith(())
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.succeedsWith(outcome)
          _ <- env.userRepository.find.succeedsWith(Some(user))
          _ <- env.passkeyRepository.findByCredentialIdAndUser.succeedsWith(Some(existingPasskey))
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
          overwriteCalls = env.conversationRepository.overwrite.calls
        yield assertTrue(
          result == ConversationResult.WriteConflict,
          overwriteCalls.head._2.amr(PassedAuthFactor.passkey).methods.contains(AuthMethodRef.swk),
        )
      },
      test("records hwk for a single-device passkey") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        val outcome = AssertionOutcome(userId, CredentialId.fromString("id"), 1L)
        val user = UserRecord.empty(userId)
        val existingPasskey = PasskeyRecord(
          id = CredentialId.fromString("id"),
          userId = userId,
          publicKey = Array.empty,
          signatureCounter = 1L,
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
        )
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.reset.succeedsWith(())
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.succeedsWith(outcome)
          _ <- env.userRepository.find.succeedsWith(Some(user))
          _ <- env.passkeyRepository.findByCredentialIdAndUser.succeedsWith(Some(existingPasskey))
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
          overwriteCalls = env.conversationRepository.overwrite.calls
        yield assertTrue(
          result == ConversationResult.WriteConflict,
          overwriteCalls.head._2.amr(PassedAuthFactor.passkey).methods.contains(AuthMethodRef.hwk),
        )
      },
    ),
    suite("offerPasskeyEnroll")(
      test("render enrollment step if user has no passkeys") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId))
        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.webAuthnService.startRegistration.succeedsWith(PasskeyCeremony("reg-req", "{}"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.offerPasskeyEnroll(authId, recordWithUser)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.PasskeyEnroll("reg-req", "{}")),
        )
      },
      test("deny access when reached without a resolved user") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.offerPasskeyEnroll(authId, baseRecord)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("finish directly when passkeys are not configured for the client") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId), grantedScope = Some(baseRecord.scope))
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testPublicSessionId = versola.oauth.session.model.PublicSessionId("public-session")
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testMac = versola.util.MAC(Array.fill(32)(4.toByte))
        for
          _ <- env.configService.getPasskeySettings.succeedsWith(None)
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextPublicSessionId.succeedsWith(testPublicSessionId)
          _ <- env.securityService.mac.succeedsWith(testMac)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
          result <- env.service.offerPasskeyEnroll(authId, recordWithUser)
        yield assertTrue(result.isInstanceOf[ConversationResult.Complete], env.passkeyRepository.listByUser.calls.isEmpty)
      },
      test("finish directly when starting the registration ceremony fails") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId), grantedScope = Some(baseRecord.scope))
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testPublicSessionId = versola.oauth.session.model.PublicSessionId("public-session")
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testMac = versola.util.MAC(Array.fill(32)(4.toByte))
        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.webAuthnService.startRegistration.failsWith(versola.oauth.challenge.passkey.WebAuthnError.PasskeysNotEnabled)
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextPublicSessionId.succeedsWith(testPublicSessionId)
          _ <- env.securityService.mac.succeedsWith(testMac)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
          result <- env.service.offerPasskeyEnroll(authId, recordWithUser)
        yield assertTrue(result.isInstanceOf[ConversationResult.Complete])
      },
      test("finish conversation if user already has passkeys") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId), grantedScope = Some(baseRecord.scope))
        val existingPasskey = PasskeyRecord(
          id = CredentialId(Array.empty),
          userId = userId,
          publicKey = Array.empty,
          signatureCounter = 0,
          deviceType = CredentialDeviceType.SingleDevice,
          backedUp = true,
          backupEligible = true,
          transports = Nil,
          attestationObject = None,
          clientDataJson = None,
          aaguid = None,
          name = None,
          lastUsedAt = None,
          createdAt = Instant.now(),
          updatedAt = Instant.now(),
        )
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testPublicSessionId = versola.oauth.session.model.PublicSessionId("public-session")
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testMac = versola.util.MAC(Array.fill(32)(4.toByte))
        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector(existingPasskey))
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextPublicSessionId.succeedsWith(testPublicSessionId)
          _ <- env.securityService.mac.succeedsWith(testMac)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
          result <- env.service.offerPasskeyEnroll(authId, recordWithUser)
        yield assertTrue(result.isInstanceOf[ConversationResult.Complete])
      },
    ),
    suite("finishPasskeyEnroll")(
      test("deny access when reached without a resolved user") {
        val env = Env()
        val enrollStep = ConversationStep.PasskeyEnroll("reg-req", "{}")
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyEnroll(authId, baseRecord, enrollStep, "resp", PasskeyName("my-passkey"))
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("pass the step on success so the caller decides what follows") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId))
        val enrollStep = ConversationStep.PasskeyEnroll("reg-req", "{}")
        val dummyPasskey = PasskeyRecord(
          id = CredentialId(Array.fill(16)(1.toByte)),
          userId = userId,
          publicKey = Array.empty,
          signatureCounter = 0,
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
        )
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testPublicSessionId = versola.oauth.session.model.PublicSessionId("public-session")
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testMac = versola.util.MAC(Array.fill(32)(4.toByte))
        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishRegistration.succeedsWith(dummyPasskey)
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextPublicSessionId.succeedsWith(testPublicSessionId)
          _ <- env.securityService.mac.succeedsWith(testMac)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
          result <- env.service.finishPasskeyEnroll(authId, recordWithUser, enrollStep, "resp", PasskeyName("my-passkey"))
        yield assertTrue(result == ConversationResult.StepPassed(recordWithUser))
      },
      test("re-render enroll step with enrollFailed flag when registration fails") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId))
        val enrollStep = ConversationStep.PasskeyEnroll("reg-req", "{}")
        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishRegistration.failsWith(versola.oauth.challenge.passkey.WebAuthnError.CeremonyFailed("fail"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyEnroll(authId, recordWithUser, enrollStep, "resp", PasskeyName("my-passkey"))
        yield assertTrue(result == ConversationResult.RenderStep(enrollStep.copy(enrollFailed = true)))
      },
    ),
    suite("skipPasskey")(
      test("pass the step so the caller decides what follows") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId))
        val testCode = versola.oauth.model.AuthorizationCode(Array.fill(32)(1.toByte))
        val testSessionId = versola.oauth.session.model.SessionId(Array.fill(32)(2.toByte))
        val testPublicSessionId = versola.oauth.session.model.PublicSessionId("public-session")
        val testAccessToken = versola.oauth.model.AccessToken(Array.fill(32)(3.toByte))
        val testMac = versola.util.MAC(Array.fill(32)(4.toByte))
        for
          _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(testCode)
          _ <- env.authPropertyGenerator.nextSessionId.succeedsWith(testSessionId)
          _ <- env.authPropertyGenerator.nextPublicSessionId.succeedsWith(testPublicSessionId)
          _ <- env.securityService.mac.succeedsWith(testMac)
          _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(testAccessToken)
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
          result <- env.service.skipPasskey(authId, recordWithUser)
        yield assertTrue(result == ConversationResult.StepPassed(recordWithUser))
      },
    ),
  )

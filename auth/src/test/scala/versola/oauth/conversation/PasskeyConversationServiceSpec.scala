package versola.oauth.conversation

import versola.antifraud.{AntifraudAction, AntifraudOutcome, RecordingAntifraudEvents}
import versola.audit.{AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
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
  RegistrationCredential,
  RegistrationFlow,
  ScopeToken,
}
import versola.oauth.conversation.CodeAlternativeFixtures as Alt
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.otp.OtpService
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.oauth.session.model.RefreshTokenFamilyId
import versola.oauth.session.{SessionRepository, UserAgentRepository}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.model.{UserId, UserRecord}
import versola.user.{UserRepository, UserService}
import versola.util.audit.AuditOutcome
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
    val antifraud = RecordingAntifraudEvents()

    val audit = CapturingAuditEvents.shared()
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
      antifraud,
      versola.antifraud.ChallengeVerifier.off,
      CodeAlternativeService.Impl(configService),
      audit,
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
    codeChallenge = Some(codeChallenge),
    codeChallengeMethod = Some(codeChallengeMethod),
    state = None,
    userId = None,
    credential = None,
    step = credentialStep,
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    responseMode = versola.oauth.authorize.model.ResponseMode.Query,
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
    dpopJkt = None,
  )

  // A minimal assertion response carrying a credential id, used as the throttle subject.
  val assertionResponse = """{"id":"cred-123"}"""

  // `startRegistration.calls` are (settings, userId, accountName, displayName)
  def enrolling(record: ConversationRecord, user: Option[UserRecord] = None) =
    val env = Env()
    for
      _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
      _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
      _ <- env.webAuthnService.startRegistration.succeedsWith(PasskeyCeremony("reg-req", "{}"))
      _ <- env.conversationRepository.overwrite.succeedsWith(true)
      _ <- env.service.offerPasskeyEnroll(authId, record.copy(userId = Some(userId)))
      call = env.webAuthnService.startRegistration.calls.head
    yield (call._3, call._4)

  // The conversation of Alt.userId on its code page, with the options of a passkey asked for.
  private val altOtp = Alt.asking(Alt.sentCode)
  private val altRecord = Alt.onCode.copy(step = altOtp)
  private val altOutcome = AssertionOutcome(Alt.userId, CredentialId.fromString("id"), 1L)
  private val altPageAgain = ConversationResult.RenderStep(Alt.sentCode.copy(passkeyFailed = true))
  private val registeringRecord = altRecord.copy(
    registrationFlow = Some(RegistrationFlow(credential = RegistrationCredential.phone, steps = Nil, roleIds = Set.empty)),
    registrationStep = Some(0),
  )

  /** The tenant has passkeys on and no class of authentication of its own. */
  private def altTenant(env: Env) =
    for
      _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
      _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
    yield ()

  private def altPassing(env: Env, stored: Option[PasskeyRecord]) =
    for
      _ <- altTenant(env)
      _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
      _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
      _ <- env.submissionLimiter.reset.succeedsWith(())
      _ <- env.webAuthnService.finishAssertion.succeedsWith(altOutcome)
      _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(Alt.userId)))
      _ <- env.passkeyRepository.findByCredentialIdAndUser.succeedsWith(stored)
      _ <- env.conversationRepository.overwrite.succeedsWith(true)
    yield ()

  private def altFailing(env: Env, error: versola.oauth.challenge.passkey.WebAuthnError) =
    for
      _ <- altTenant(env)
      _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
      _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
      _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
      _ <- env.webAuthnService.finishAssertion.failsWith(error)
      _ <- env.conversationRepository.overwrite.succeedsWith(true)
    yield ()

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
      test("fail, handing out no options, when the write of the ceremony was lost") {
        val env = Env()
        for
          _ <- env.webAuthnService.startAssertion.succeedsWith(PasskeyCeremony("req-state", "{}"))
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          exit <- env.service.startPasskeyAssertion(authId, baseRecord, credentialStep, passkeySettings).exit
        yield assertTrue(exit.isFailure)
      },
      test("restrict a passkey-only step to the passkeys of the identified user") {
        val env = Env()
        val step = credentialStep.copy(passkeyOnly = true)
        val record = baseRecord.copy(userId = Some(userId), step = step)
        for
          _ <- env.webAuthnService.startUserAssertion.succeedsWith(PasskeyCeremony("req-state", "{}"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.startPasskeyAssertion(authId, record, step, passkeySettings)
        yield assertTrue(
          result == "{}",
          env.webAuthnService.startUserAssertion.calls.head == (passkeySettings, userId),
          env.webAuthnService.startAssertion.calls.isEmpty,
          env.conversationRepository.overwrite.calls.head._2.step == step.copy(passkeyRequest = Some("req-state")),
        )
      },
      test("never start a discoverable assertion for a passkey-only step without a user") {
        val env = Env()
        val step = credentialStep.copy(passkeyOnly = true)
        for
          exit <- env.service.startPasskeyAssertion(authId, baseRecord.copy(step = step), step, passkeySettings).exit
        yield assertTrue(
          exit.isFailure,
          env.webAuthnService.startAssertion.calls.isEmpty,
          env.webAuthnService.startUserAssertion.calls.isEmpty,
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
    suite("promptPasskey")(
      test("render the passkey-only credential step for an identified user whose flow offers passkeys") {
        val env = Env()
        val record = baseRecord.copy(userId = Some(userId))
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.promptPasskey(authId, record)
        yield assertTrue(
          result == ConversationResult.RenderStep(
            ConversationStep.Credential(record.authFlow.primary.credentials, false, true, passkeyOnly = true),
          ),
        )
      },
      test("deny when the conversation has no user") {
        val env = Env()
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.promptPasskey(authId, baseRecord)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("deny when the flow offers no passkey") {
        val env = Env()
        val record = baseRecord.copy(userId = Some(userId), authFlow = baseRecord.authFlow.copy(passkey = None))
        for
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.promptPasskey(authId, record)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
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
      test("deny, rather than offer another try, when a passkey-only assertion fails verification") {
        val env = Env()
        val step = credentialStep.copy(passkeyOnly = true, passkeyRequest = Some("req-state"))
        val record = baseRecord.copy(userId = Some(userId), step = step)
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.failsWith(versola.oauth.challenge.passkey.WebAuthnError.AssertionFailed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, record, assertionResponse, None)
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("deny a passkey-only assertion that resolves to another user, and record no passkey as passed") {
        val env = Env()
        val step = credentialStep.copy(passkeyOnly = true, passkeyRequest = Some("req-state"))
        val victim = UserId(UUID.randomUUID())
        val record = baseRecord.copy(userId = Some(victim), step = step)
        val outcome = AssertionOutcome(userId, CredentialId.fromString("id"), 1L)
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.reset.succeedsWith(())
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.succeedsWith(outcome)
          _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- env.passkeyRepository.findByCredentialIdAndUser.succeedsWith(None)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.finishPasskeyAssertion(authId, record, assertionResponse, None)
          written = env.conversationRepository.overwrite.calls.map(_._2)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          written.forall(!_.amr.contains(PassedAuthFactor.passkey)),
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
    suite("startPasskeyOnCode")(
      test(
        "starts a discoverable ceremony with no allow list, the same for a user, a number that is nobody's and a registration, and keeps it on the code step",
      ) {
        val env = Env()
        val nobody = Alt.onCode.copy(userId = None, userPhone = None)
        for
          _ <- altTenant(env)
          _ <- env.webAuthnService.startAssertion.succeedsWith(PasskeyCeremony("req-state", "{}"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          user <- env.service.startPasskeyOnCode(authId, Alt.onCode, Alt.sentCode, passkeySettings)
          noUser <- env.service.startPasskeyOnCode(authId, nobody, Alt.sentCode, passkeySettings)
          registration <- env.service.startPasskeyOnCode(authId, registeringRecord, Alt.sentCode, passkeySettings)
        yield assertTrue(
          user == Some("{}"),
          noUser == user,
          registration == user,
          env.webAuthnService.startAssertion.calls.size == 3,
          // A ceremony restricted to the user's passkeys would list them for an account and nothing for a number with none.
          env.webAuthnService.startUserAssertion.calls.isEmpty,
          // What was sent, and when, is as it was: only the ceremony is added to the step.
          env.conversationRepository.overwrite.calls.map(_._2.step).head == Alt.asking(Alt.sentCode),
        )
      },
      test("hands out no options for a ceremony that was not stored: a lost write answers None") {
        val env = Env()
        for
          _ <- altTenant(env)
          _ <- env.webAuthnService.startAssertion.succeedsWith(PasskeyCeremony("req-state", "{}"))
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          result <- env.service.startPasskeyOnCode(authId, Alt.onCode, Alt.sentCode, passkeySettings)
        yield assertTrue(result.isEmpty)
      },
      test("answers None, starting nothing and writing nothing, when the page does not offer a passkey: no flow passkey, or one passed already") {
        val env = Env()
        for
          _ <- altTenant(env)
          noFlow <- env.service.startPasskeyOnCode(authId, Alt.onCode.copy(authFlow = AuthFlow.default), Alt.sentCode, passkeySettings)
          passed <- env.service.startPasskeyOnCode(authId, Alt.onCode.copy(amr = Map(Alt.passedPasskey())), Alt.sentCode, passkeySettings)
        yield assertTrue(
          noFlow.isEmpty,
          passed.isEmpty,
          env.webAuthnService.startAssertion.calls.isEmpty,
          env.conversationRepository.overwrite.calls.isEmpty,
        )
      },
    ),
    suite("checkPasskeyOnCode")(
      test(
        "a passed passkey is recorded as passed with the methods the passkey step records, the step is left without the ceremony, and the event says the code was not entered",
      ) {
        val env = Env()
        for
          _ <- altPassing(env, Some(Alt.aPasskey.copy(deviceType = CredentialDeviceType.SingleDevice)))
          result <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, assertionResponse, None)
          written = env.conversationRepository.overwrite.calls.map(_._2)
        yield result match
          case ConversationResult.StepPassed(passed) =>
            assertTrue(
              passed.amr(PassedAuthFactor.passkey).methods == Set(AuthMethodRef.hwk, AuthMethodRef.user, AuthMethodRef.mfa),
              passed.userId == Some(Alt.userId),
              passed.version == altRecord.version + 1,
              passed.step == Alt.sentCode,
              written.map(_.amr.keySet) == List(Set(PassedAuthFactor.passkey)),
              env.webAuthnService.startRegistration.calls.isEmpty,
              env.antifraud.steps.map(s => (s.action, s.outcome)) == List((AntifraudAction.otp_verify, AntifraudOutcome.passkey_alternative)),
            )
          case _ => assertNever("the passkey did not pass")
      },
      test("a synced passkey is recorded as a software-secured key, as it is everywhere") {
        val env = Env()
        for
          _ <- altPassing(env, Some(Alt.aPasskey))
          result <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, assertionResponse, None)
        yield result match
          case ConversationResult.StepPassed(passed) => assertTrue(passed.amr(PassedAuthFactor.passkey).methods.contains(AuthMethodRef.swk))
          case _ => assertNever("the passkey did not pass")
      },
      test(
        "a failed assertion re-renders the same code page with that said, what was sent and when untouched, and is counted by the limits of the assertion",
      ) {
        val env = Env()
        for
          _ <- altFailing(env, versola.oauth.challenge.passkey.WebAuthnError.AssertionFailed)
          result <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, assertionResponse, Some("1.2.3.4"))
        yield assertTrue(
          result == altPageAgain,
          env.submissionLimiter.recordLimit.calls.map(_._2).toSet == Set("ip:1.2.3.4", "cred-123"),
          env.conversationRepository.overwrite.calls.forall(!_._2.amr.contains(PassedAuthFactor.passkey)),
          env.antifraud.steps.isEmpty,
        )
      },
      test("a credential that does not exist is the same: the code page again, and no access page") {
        val env = Env()
        for
          _ <- altFailing(env, versola.oauth.challenge.passkey.WebAuthnError.CredentialNotFound)
          result <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, assertionResponse, None)
        yield assertTrue(result == altPageAgain)
      },
      test("a payload with no usable credential id is the same too") {
        val env = Env()
        for
          _ <- altTenant(env)
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(None)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, "not json", None)
        yield assertTrue(result == altPageAgain)
      },
      test("an assertion that resolves to another user brings back the same code page, counted as a failed one, and records no passkey") {
        val env = Env()
        val stranger = AssertionOutcome(UserId(UUID.randomUUID()), CredentialId.fromString("id"), 1L)
        for
          _ <- altPassing(env, None)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.webAuthnService.finishAssertion.succeedsWith(stranger)
          result <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, assertionResponse, Some("1.2.3.4"))
        yield assertTrue(
          result == altPageAgain,
          env.submissionLimiter.recordLimit.calls.map(_._2).toSet == Set("ip:1.2.3.4", "cred-123"),
          // A valid passkey of someone else does not clear the failures of the address or the credential.
          env.submissionLimiter.reset.calls.isEmpty,
          env.conversationRepository.overwrite.calls.forall(!_._2.amr.contains(PassedAuthFactor.passkey)),
          env.antifraud.steps.isEmpty,
        )
      },
      test("a passkey asserted in a conversation with no user, or in a registration, is the same: the code page again, and none is recorded") {
        val env = Env()
        for
          _ <- altPassing(env, None)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          noUser <- env.service.checkPasskeyOnCode(authId, altRecord.copy(userId = None), altOtp, assertionResponse, None)
          registration <- env.service.checkPasskeyOnCode(authId, registeringRecord, altOtp, assertionResponse, None)
        yield assertTrue(
          noUser == altPageAgain,
          registration == altPageAgain,
          env.conversationRepository.overwrite.calls.forall(!_._2.amr.contains(PassedAuthFactor.passkey)),
        )
      },
      test("a mismatch that keeps being repeated is stopped by the limits of the assertion, as any failure is") {
        val env = Env()
        val stranger = AssertionOutcome(UserId(UUID.randomUUID()), CredentialId.fromString("id"), 1L)
        for
          _ <- altPassing(env, None)
          _ <- env.webAuthnService.finishAssertion.succeedsWith(stranger)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          first <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, assertionResponse, Some("1.2.3.4"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          later <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, assertionResponse, Some("1.2.3.4"))
        yield assertTrue(first == altPageAgain, later == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("a banned credential or address is denied, as it is on any passkey step") {
        val env = Env()
        for
          _ <- altTenant(env)
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkPasskeyOnCode(authId, altRecord, altOtp, assertionResponse, Some("1.2.3.4"))
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied))
      },
      test("an assertion with no ceremony pending is a bad request, and nothing is looked at") {
        val env = Env()
        for
          _ <- altTenant(env)
          result <- env.service.checkPasskeyOnCode(authId, Alt.onCode, Alt.sentCode, assertionResponse, None)
        yield assertTrue(result == ConversationResult.BadRequest, env.webAuthnService.finishAssertion.calls.isEmpty)
      },
      test("an assertion for a page that no longer offers a passkey is a bad request, whatever ceremony is pending") {
        val env = Env()
        for
          _ <- altTenant(env)
          passed <- env.service.checkPasskeyOnCode(authId, altRecord.copy(amr = Map(Alt.passedPasskey())), altOtp, assertionResponse, None)
          noFlow <- env.service.checkPasskeyOnCode(authId, altRecord.copy(authFlow = AuthFlow.default), altOtp, assertionResponse, None)
        yield assertTrue(
          passed == ConversationResult.BadRequest,
          noFlow == ConversationResult.BadRequest,
          env.webAuthnService.finishAssertion.calls.isEmpty,
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
      test("name the account by the phone number the user signed in with") {
        for names <- enrolling(baseRecord.copy(credential = Some(Right(versola.util.Phone("+77001234567")))))
        yield assertTrue(names == ("+77001234567", "+77001234567"))
      },
      test("name the account by the email the user signed in with") {
        for names <- enrolling(baseRecord.copy(credential = Some(Left(versola.util.Email("dana@example.com")))))
        yield assertTrue(names == ("dana@example.com", "dana@example.com"))
      },
      test("name the account by the login of a login and password sign-in") {
        for names <- enrolling(baseRecord.copy(userLogin = Some(versola.user.model.Login("dana"))))
        yield assertTrue(names == ("dana", "dana"))
      },
      test("keep the user's own name as the secondary label, and the sign-in credential as the account") {
        val claims = zio.json.ast.Json.Obj("name" -> zio.json.ast.Json.Str("Dana Smith"))
        for names <- enrolling(baseRecord.copy(credential = Some(Right(versola.util.Phone("+77001234567"))), userClaims = Some(claims)))
        yield assertTrue(names == ("+77001234567", "Dana Smith"))
      },
      test("fall back to the user's email, then phone, when there is no sign-in credential") {
        val both = baseRecord.copy(userEmail = Some(versola.util.Email("dana@example.com")), userPhone = Some(versola.util.Phone("+77001234567")))
        for
          withEmail <- enrolling(both)
          phoneOnly <- enrolling(both.copy(userEmail = None))
        yield assertTrue(withEmail._1 == "dana@example.com", phoneOnly._1 == "+77001234567")
      },
      test("never show the user id: with nothing else the service's own name is the account") {
        for names <- enrolling(baseRecord)
        yield assertTrue(names == (passkeySettings.rpName, passkeySettings.rpName), !names._1.contains(userId.toString))
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
          _ <- env.authPropertyGenerator.nextRefreshTokenFamilyId.succeedsWith(RefreshTokenFamilyId("family-1"))
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
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
          _ <- env.authPropertyGenerator.nextRefreshTokenFamilyId.succeedsWith(RefreshTokenFamilyId("family-1"))
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
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
          _ <- env.authPropertyGenerator.nextRefreshTokenFamilyId.succeedsWith(RefreshTokenFamilyId("family-1"))
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
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
          _ <- env.authPropertyGenerator.nextRefreshTokenFamilyId.succeedsWith(RefreshTokenFamilyId("family-1"))
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
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
          _ <- env.authPropertyGenerator.nextRefreshTokenFamilyId.succeedsWith(RefreshTokenFamilyId("family-1"))
          _ <- env.authorizationCodeRepository.create.succeedsWith(())
          _ <- env.sessionRepository.create.succeedsWith(())
          _ <- env.conversationRepository.delete.succeedsWith(true)
          _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
          _ <- env.configService.getSessionTtl.succeedsWith(zio.Duration.fromSeconds(86400))
          _ <- env.configService.getUserAgentTtl.succeedsWith(zio.Duration.fromSeconds(15552000))
          _ <- env.configService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(java.util.UUID.randomUUID())
          _ <- env.userAgentRepository.create.succeedsWith(())
          result <- env.service.skipPasskey(authId, recordWithUser)
        yield assertTrue(result == ConversationResult.StepPassed(recordWithUser))
      },
    ),
    suite("audit log")(
      test("a verified assertion records login.step success for the user the passkey belongs to") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        val outcome = AssertionOutcome(userId, CredentialId.fromString("id"), 1L)
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.reset.succeedsWith(())
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.succeedsWith(outcome)
          _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- env.passkeyRepository.findByCredentialIdAndUser.succeedsWith(None)
          _ <- env.passkeyRepository.listByUser.succeedsWith(Vector.empty)
          _ <- env.webAuthnService.startRegistration.succeedsWith(PasskeyCeremony("reg-req", "{}"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, None)
          drafts <- env.audit.recorded
        yield assertTrue(
          drafts.map(d => (d.kind, d.outcome, d.reason)) == List((AuthAuditKinds.LoginStep, AuditOutcome.success, None)),
          drafts.head.userId == Some(userId.asInstanceOf[java.util.UUID]),
          drafts.head.details == AuthAuditDetails.LoginStep(step = "passkey", conversationId = authId.toString, kind = "login"),
        )
      },
      test("an assertion that did not verify records login.step failure invalid_assertion, and no user") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishAssertion.failsWith(versola.oauth.challenge.passkey.WebAuthnError.CeremonyFailed("fail"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, Some("1.2.3.4"))
          drafts <- env.audit.recorded
        yield assertTrue(
          drafts.map(d => (d.outcome, d.reason)) == List((AuditOutcome.failure, Some("invalid_assertion"))),
          drafts.head.userId.isEmpty,
          // the credential id the client sent is the throttle's subject, not an event's
          !drafts.head.details.toJson.toString.contains("cred-123"),
        )
      },
      test("a throttled subject records login.step denied throttled, without verifying anything") {
        val env = Env()
        val recordWithRequest = baseRecord.copy(step = credentialStep.copy(passkeyRequest = Some("req-state")))
        for
          _ <- env.webAuthnService.credentialIdFromResponse.succeedsWith(Some("cred-123"))
          _ <- env.submissionLimiter.statusForSubjects.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.finishPasskeyAssertion(authId, recordWithRequest, assertionResponse, Some("1.2.3.4"))
          drafts <- env.audit.recorded
        yield assertTrue(
          drafts.map(d => (d.outcome, d.reason)) == List((AuditOutcome.denied, Some("throttled"))),
          env.webAuthnService.finishAssertion.calls.isEmpty,
        )
      },
      test("an enrolled passkey records account.passkey_enrolled with a prefix of the credential id and its device type") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId))
        val enrollStep = ConversationStep.PasskeyEnroll("reg-req", "{}")
        val enrolled = PasskeyRecord(
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
          createdAt = Instant.EPOCH,
          updatedAt = Instant.EPOCH,
        )
        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishRegistration.succeedsWith(enrolled)
          _ <- env.service.finishPasskeyEnroll(authId, recordWithUser, enrollStep, "resp", PasskeyName("my-passkey"))
          drafts <- env.audit.recorded
        yield assertTrue(
          drafts.map(_.kind) == List(AuthAuditKinds.AccountPasskeyEnrolled),
          drafts.head.userId == Some(userId.asInstanceOf[java.util.UUID]),
          drafts.head.details == AuthAuditDetails.Passkey(
            credentialPrefix = Some(enrolled.id.toString.take(9)),
            deviceType = Some("SingleDevice"),
          ),
        )
      },
      test("a registration that failed records no account.passkey_enrolled") {
        val env = Env()
        val recordWithUser = baseRecord.copy(userId = Some(userId))
        for
          _ <- env.configService.getPasskeySettings.succeedsWith(Some(passkeySettings))
          _ <- env.webAuthnService.finishRegistration.failsWith(versola.oauth.challenge.passkey.WebAuthnError.CeremonyFailed("fail"))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.finishPasskeyEnroll(
            authId,
            recordWithUser,
            ConversationStep.PasskeyEnroll("reg-req", "{}"),
            "resp",
            PasskeyName("my-passkey"),
          )
          drafts <- env.audit.recorded
        yield assertTrue(drafts.isEmpty)
      },
    ),
  )

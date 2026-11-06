package versola.oauth.conversation

import versola.antifraud.{
  AntifraudAction,
  AntifraudOutcome,
  Challenge,
  ChallengeResult,
  ChallengeSubject,
  ChallengeVerifier,
  Decision,
  DecisionAction,
  RecordingAntifraudEvents,
}
import versola.audit.{AuthAuditDetails, AuthAuditKinds, CapturingAuditEvents}
import versola.auth.TestEnvConfig
import versola.auth.model.OtpCode
import versola.oauth.authorize.AcrResolutionService
import versola.oauth.challenge.passkey.{PasskeyRepository, WebAuthnService}
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFlow, ClientId, PrimaryCredential, ScopeToken}
import versola.oauth.client.model.{
  AuthFlow,
  AuthMethodRef,
  ClientId,
  PassedAuthFactor,
  PassedFactorRecord,
  PrimaryCredential,
  RegistrationCredential,
  RegistrationFlow,
  ScopeToken,
}
import versola.oauth.conversation.CodeAlternativeFixtures as Alt
import versola.oauth.conversation.limit.{ChallengeType, LimitStatus, SubmissionLimiter}
import versola.oauth.conversation.model.{AuthId, CodeAlternative, ConversationRecord, ConversationStep}
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
import versola.util.audit.AuditOutcome
import versola.util.{AuthPropertyGenerator, CoreConfig, Email, EnvName, MAC, Phone, Secret, SecureRandom, SecurityService, UnitSpecBase}
import zio.http.URL
import zio.json.ast
import zio.test.*
import zio.{Ref, ZIO, durationInt}

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

  class Env(
      smsAllowed: Boolean = true,
      afterRequested: zio.UIO[Unit] = ZIO.unit,
      decision: Decision = Decision.allow,
      val challengeVerifier: ChallengeVerifier = ChallengeVerifier.off,
      fakeDelay: zio.Duration = zio.Duration.Zero,
      smsWouldRefuse: Boolean = false,
  ):
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
    val acrResolver = stub[AcrResolutionService]
    val config = TestEnvConfig.coreConfig
    val userAgentRepository = stub[UserAgentRepository]
    val secureRandom = stub[SecureRandom]
    val userService = stub[UserService]
    val consentService = stub[versola.oauth.consent.ConsentService]
    val antifraud = RecordingAntifraudEvents(smsAllowed, afterRequested, decision, fakeDelay, smsWouldRefuse)
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
      challengeVerifier,
      CodeAlternativeService.Impl(configService),
      audit,
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

  private def stepUp(record: ConversationRecord) =
    record.copy(priorSessionId = Some(MAC(Array.fill(32)(2.toByte))), targetAcr = Some(Acr("mfa")))

  /** Issues one challenge whatever the score (unless `issues` is false, as when it fails), and answers every solution with `verdict`. */
  class FakeVerifier(verdict: ChallengeResult, limit: Int = 3, issues: Boolean = true) extends ChallengeVerifier:
    override def pause: zio.Duration = 20.seconds
    val challenge: Challenge = Challenge(kind = "pow", token = "issued-token", level = 17, params = Map("seed" -> "s", "difficulty" -> "17"))
    override def issue(subject: ChallengeSubject, score: Int): zio.UIO[Option[Challenge]] = ZIO.succeed(Option.when(issues)(challenge))
    override def verify(subject: ChallengeSubject, token: String, solution: String): zio.UIO[ChallengeResult] = ZIO.succeed(verdict)
    override def enabled: Boolean = true
    override def maxAttempts: Int = limit

  private val challenging = Decision(DecisionAction.Challenge, 61, List("phone_velocity"))
  private val phone = Right(Phone("+491512345678"))
  private val answered = ProofInput.Answered("issued-token", "42")

  /** What a request for a code came to, for a decision and a verifier: the result, the steps told to antifraud, and the env. */
  private def request(env: Env, proof: ProofInput, conversation: ConversationRecord = initialConversation, failures: Int = 1) =
    for
      _ <- env.conversationRepository.consumeChallenge.succeedsWith(true)
      _ <- env.conversationRepository.recordChallengeFailure.succeedsWith(failures)
      _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
      _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
      _ <- env.configService.find.succeedsWith(None)
      // As the real service makes it: a code that is forced fake has nothing behind it.
      _ <- env.otpService.prepareOtp.returnsZIO((_, _, _, _, forceFake) => ZIO.succeed(if forceFake then realOtp.copy(real = None) else realOtp))
      _ <- env.conversationRepository.overwrite.succeedsWith(true)
      _ <- env.otpService.sendOtp.succeedsWith(())
      result <- env.service.prepareInitialOtp(authId, conversation, phone, factorIndex = 0, proof)
    yield result

  /** A conversation of a user the number belongs to, on its way to a code, with a flow that can ask for a passkey. */
  private val knownUser = initialConversation.copy(
    userId = Some(Alt.userId),
    userPhone = Some(Alt.phone),
    authFlow = Alt.flowWithPasskey,
  )

  /** The tenant has passkeys on. What the user has enrolled is not asked: the page does not depend on it. */
  private def passkeyTenant(env: Env) =
    for
      _ <- env.configService.getPasskeySettings.succeedsWith(Some(Alt.passkeySettings))
      _ <- env.configService.getAcrVocabulary.succeedsWith(Map.empty)
    yield ()

  val spec = suite("OtpConversationService")(
    suite("proof of work")(
      test("a plain send takes one step of the request limit") {
        val env = Env()
        for
          _ <- request(env, ProofInput.NoScript)
        yield assertTrue(env.submissionLimiter.tryAcquire.calls.size == 1)
      },
      test("a challenged send takes one step in all: the request that is challenged pays, the one that carries the answer does not") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          asked <- request(env, ProofInput.Asked)
          afterAsk = env.submissionLimiter.tryAcquire.calls.size
          sent <- request(env, answered)
        yield assertTrue(
          asked.isInstanceOf[ConversationResult.Challenged],
          afterAsk == 1,
          sent.isInstanceOf[ConversationResult.RenderStep],
          env.submissionLimiter.tryAcquire.calls.size == 1,
          env.otpService.sendOtp.calls.size == 1,
        )
      },
      test("a request left pending takes one step in all and is counted once: the ask after the pause is that request made again") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          left <- request(env, ProofInput.NoScript)
          pendingStep = env.conversationRepository.overwrite.calls.map(_._2.step).head
          afterLeft = env.submissionLimiter.tryAcquire.calls.size
          _ <- TestClock.adjust(20.seconds)
          // Posted plain, the ask is honoured now, and judged again (a block, a fake and the lists still apply).
          sent <- request(env, ProofInput.NoScript, initialConversation.copy(step = pendingStep))
        yield assertTrue(
          left.isInstanceOf[ConversationResult.RenderStep],
          afterLeft == 1,
          sent.isInstanceOf[ConversationResult.RenderStep],
          env.submissionLimiter.tryAcquire.calls.size == 1,
          // The first is counted (not repeated), the ask is not.
          env.antifraud.decisions == List(false, true),
          env.otpService.sendOtp.calls.size == 1,
          env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.challenged, AntifraudOutcome.challenge_passed, AntifraudOutcome.sent),
        )
      },
      test("a plain post's ask before the pause is over is answered with the pending page: no code, no error, nothing charged, counted or recorded") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- request(env, ProofInput.NoScript)
          pendingStep = env.conversationRepository.overwrite.calls.map(_._2.step).head
          pending = pendingStep.asInstanceOf[ConversationStep.Otp]
          stepsBefore = env.antifraud.steps.size
          _ <- TestClock.adjust(19.seconds)
          asked <- request(env, ProofInput.NoScript, initialConversation.copy(step = pendingStep))
          posted <- request(env, ProofInput.NoScript, initialConversation.copy(step = pendingStep))
        yield assertTrue(
          pending.pendingUntil == Some(java.time.Instant.EPOCH.plusSeconds(20)),
          pending.pauseRemaining(java.time.Instant.EPOCH.plusSeconds(19)) == 1,
          asked == ConversationResult.RenderStep(pendingStep),
          posted == ConversationResult.RenderStep(pendingStep),
          env.submissionLimiter.tryAcquire.calls.size == 1,
          env.antifraud.decisions == List(false),
          env.antifraud.steps.size == stepsBefore,
          env.otpService.sendOtp.calls.isEmpty,
          env.conversationRepository.overwrite.calls.size == 1,
        )
      },
      test(
        "an ask after the pause sends the code once, with one limiter step and one velocity record in all, the limiter and the counters never asked again",
      ) {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- request(env, ProofInput.NoScript)
          pendingStep = env.conversationRepository.overwrite.calls.map(_._2.step).head
          _ <- TestClock.adjust(20.seconds)
          sent <- request(env, ProofInput.NoScript, initialConversation.copy(step = pendingStep))
        yield assertTrue(
          sent.isInstanceOf[ConversationResult.RenderStep],
          env.otpService.sendOtp.calls.size == 1,
          env.submissionLimiter.tryAcquire.calls.size == 1,
          env.antifraud.decisions == List(false, true),
        )
      },
      test("an ask after the pause is still judged: a block refuses it") {
        val env = Env(decision = Decision(DecisionAction.Block, 90, List("deny_list:ip")), challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        val pendingStep = realOtp.copy(real = None, timesRequested = 0, pendingUntil = Some(java.time.Instant.EPOCH))
        for
          result <- request(env, ProofInput.Asked, initialConversation.copy(step = pendingStep))
        yield assertTrue(result == ConversationResult.RenderStep(ConversationStep.AccessDenied), env.otpService.sendOtp.calls.isEmpty)
      },
      test("a step stored without a pause (before there was one) is honoured at once") {
        val env = Env(challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        val pendingStep = realOtp.copy(real = None, timesRequested = 0)
        for
          sent <- request(env, ProofInput.NoScript, initialConversation.copy(step = pendingStep))
        yield assertTrue(sent.isInstanceOf[ConversationResult.RenderStep], env.otpService.sendOtp.calls.size == 1)
      },
      test("the pause is told as a challenge of its own kind: the pending step is challenged, the honoured ask passed") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- request(env, ProofInput.NoScript)
          pendingStep = env.conversationRepository.overwrite.calls.map(_._2.step).head
          _ <- TestClock.adjust(20.seconds)
          _ <- request(env, ProofInput.NoScript, initialConversation.copy(step = pendingStep))
        yield assertTrue(env.antifraud.kinds == List(("pause", AntifraudOutcome.challenged), ("pause", AntifraudOutcome.challenge_passed)))
      },
      test(
        "a script's ask from the pending step, while a proof of work can be asked for, is given the challenge at once: no pause, nothing sent, nothing charged again",
      ) {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- request(env, ProofInput.NoScript)
          pendingStep = env.conversationRepository.overwrite.calls.map(_._2.step).head
          // The pause is far from over.
          asked <- request(env, ProofInput.Asked, initialConversation.copy(step = pendingStep))
        yield assertTrue(
          asked.isInstanceOf[ConversationResult.Challenged],
          env.otpService.sendOtp.calls.isEmpty,
          env.submissionLimiter.tryAcquire.calls.size == 1,
          env.antifraud.decisions == List(false, true),
          // The challenge is the work's, counted as a pow challenge; the pause is only counted for asks it held back.
          env.antifraud.kinds == List(("pause", AntifraudOutcome.challenged), ("pow", AntifraudOutcome.challenged)),
        )
      },
      test("the solved answer to that challenge sends the code, still on the one step of the limit") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- request(env, ProofInput.NoScript)
          pendingStep = env.conversationRepository.overwrite.calls.map(_._2.step).head
          sent <- request(env, answered, initialConversation.copy(step = pendingStep))
        yield assertTrue(
          sent.isInstanceOf[ConversationResult.RenderStep],
          env.otpService.sendOtp.calls.size == 1,
          env.submissionLimiter.tryAcquire.calls.size == 1,
        )
      },
      test("with the proof of work off a script's ask from the pending step waits the pause too, as the only friction there is") {
        val env = Env(decision = challenging)
        val pendingStep = realOtp.copy(real = None, timesRequested = 0, pendingUntil = Some(java.time.Instant.EPOCH.plusSeconds(20)))
        for
          early <- request(env, ProofInput.Asked, initialConversation.copy(step = pendingStep))
          _ <- TestClock.adjust(20.seconds)
          sent <- request(env, ProofInput.Asked, initialConversation.copy(step = pendingStep))
        yield assertTrue(
          early == ConversationResult.RenderStep(pendingStep),
          sent.isInstanceOf[ConversationResult.RenderStep],
          env.otpService.sendOtp.calls.size == 1,
          env.antifraud.kinds == List(("pause", AntifraudOutcome.challenge_passed)),
        )
      },
      test("an answer that is wrong or forged is charged like any request, from the pending step too") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Failed(1)))
        val pendingStep = realOtp.copy(real = None, timesRequested = 0)
        for
          _ <- request(env, answered)
          _ <- request(env, answered, initialConversation.copy(step = pendingStep))
        yield assertTrue(env.submissionLimiter.tryAcquire.calls.size == 2, env.antifraud.decisions == List(false, false))
      },
      test("a ban refuses even a request that carries a right answer, and charges nothing") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Banned)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, answered)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.submissionLimiter.tryAcquire.calls.isEmpty,
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("a Challenge decision asks a client that can answer for a proof, sending nothing and writing nothing") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          result <- request(env, ProofInput.Asked)
          steps = env.antifraud.steps
        yield assertTrue(
          result == ConversationResult.Challenged(env.challengeVerifier.asInstanceOf[FakeVerifier].challenge),
          steps.map(_.outcome) == List(AntifraudOutcome.challenged),
          steps.flatMap(_.decision) == List(challenging),
          env.antifraud.smsAsked.isEmpty,
          env.conversationRepository.overwrite.calls.isEmpty,
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("a Challenge decision for a request that cannot answer is left on the code step with nothing sent, to be asked for there") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          result <- request(env, ProofInput.NoScript)
          written = env.conversationRepository.overwrite.calls.map(_._2)
        yield assertTrue(
          result.isInstanceOf[ConversationResult.RenderStep],
          written.map(_.step).collect { case otp: ConversationStep.Otp => otp.pending } == List(true),
          written.map(_.credential) == List(Some(phone)),
          env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.challenged),
          env.otpService.sendOtp.calls.isEmpty,
          env.antifraud.smsAsked.isEmpty,
        )
      },
      test("with the verifier off a Challenge is sent as a plain send, whoever asks") {
        val env = Env(decision = challenging)
        for
          _ <- request(env, ProofInput.NoScript)
        yield assertTrue(env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.sent))
      },
      test("a verifier that fails to make a challenge fails open the same way") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed, issues = false))
        for
          _ <- request(env, ProofInput.Asked)
        yield assertTrue(env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.sent))
      },
      test("a right solution sends the code once, judging the request again but not counting it again") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          result <- request(env, answered)
        yield assertTrue(
          result.isInstanceOf[ConversationResult.RenderStep],
          env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.challenge_passed, AntifraudOutcome.sent),
          env.antifraud.decisions == List(true),
          env.otpService.sendOtp.calls.size == 1,
        )
      },
      test("a solution does not override a block: the request is judged again, and Block still refuses it") {
        val env = Env(decision = Decision(DecisionAction.Block, 90, List("deny_list")), challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          result <- request(env, answered)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.blocked),
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("a solution to a challenge the request no longer needs sends as any request would") {
        val env = Env(challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- request(env, answered)
        yield assertTrue(env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.sent))
      },
      test("a request with a garbage proof is counted like any other, and the proof does not lower its decision") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Failed(1)))
        for
          result <- request(env, ProofInput.Answered("junk", "1"))
        yield assertTrue(
          env.antifraud.decisions == List(false),
          result.isInstanceOf[ConversationResult.Challenged],
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("two callers racing with the same solved token send one code, record one send, and the loser gives its charge back") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          taken <- Ref.make(false)
          _ <- env.conversationRepository.consumeChallenge.succeedsWith(true)
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.configService.find.succeedsWith(None)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          // The versioned write: whoever gets there first wins, the other finds the version moved on.
          _ <- env.conversationRepository.overwrite.returnsZIO(_ => taken.getAndSet(true).map(!_))
          _ <- env.otpService.sendOtp.succeedsWith(())
          results <- ZIO.collectAllPar(List.fill(2)(env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, answered)))
        yield assertTrue(
          results.count(_.isInstanceOf[ConversationResult.RenderStep]) == 1,
          results.contains(ConversationResult.WriteConflict),
          env.otpService.sendOtp.calls.size == 1,
          env.antifraud.steps.count(_.outcome == AntifraudOutcome.sent) == 1,
          env.antifraud.smsAsked.size == 2,
          env.antifraud.smsReleased == 1,
        )
      },
      test("a wrong solution is recorded and the challenge put again, and sends nothing") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Failed(1)))
        for
          result <- request(env, answered)
        yield assertTrue(
          result.isInstanceOf[ConversationResult.Challenged],
          env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.challenge_failed, AntifraudOutcome.challenged),
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("the wrong solution that reaches the limit refuses the request") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Failed(1), limit = 3))
        for
          // The count is the conversation's: this is its third wrong solution, whatever the verifier says.
          result <- request(env, answered, failures = 3)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.challenge_failed, AntifraudOutcome.blocked),
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("the wrong solutions are counted in the conversation, one statement each, and the count is what the limit goes by") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Failed(1), limit = 3))
        for
          _ <- request(env, answered, failures = 2)
          afterTwo = env.conversationRepository.recordChallengeFailure.calls.size
          refused <- request(env, answered, failures = 3)
        yield assertTrue(
          afterTwo == 1,
          env.conversationRepository.recordChallengeFailure.calls.size == 2,
          env.conversationRepository.consumeChallenge.calls.isEmpty,
          refused == ConversationResult.RenderStep(ConversationStep.AccessDenied),
        )
      },
      test("a conversation that has made too many wrong solutions is refused when it asks again, with no answer: the token does not say how many") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed, limit = 3))
        for
          result <- request(env, ProofInput.Asked, initialConversation.copy(challengeFailed = 3))
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.blocked),
        )
      },
      test("a right solution is spent in the conversation, at the count it was issued for") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- request(env, answered, initialConversation.copy(challengeSolved = 2))
        yield assertTrue(
          env.conversationRepository.consumeChallenge.calls.map(_._2) == List(2),
          env.conversationRepository.recordChallengeFailure.calls.isEmpty,
        )
      },
      test("a solved token used a second time is not a solution: it is counted as a wrong one, charged, and sends nothing") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Passed))
        for
          _ <- env.conversationRepository.consumeChallenge.succeedsWith(false)
          _ <- env.conversationRepository.recordChallengeFailure.succeedsWith(1)
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.configService.find.succeedsWith(None)
          result <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, answered)
        yield assertTrue(
          result.isInstanceOf[ConversationResult.Challenged],
          env.submissionLimiter.tryAcquire.calls.size == 1,
          env.antifraud.decisions == List(false),
          env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.challenge_failed, AntifraudOutcome.challenged),
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("a solution that came too late is a new challenge, not a failure") {
        val env = Env(decision = challenging, challengeVerifier = FakeVerifier(ChallengeResult.Expired))
        for
          result <- request(env, answered)
        yield assertTrue(result.isInstanceOf[ConversationResult.Challenged], env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.challenged))
      },
    ),
    suite("antifraud decision")(
      test("a Block decision refuses the request, records it as blocked with the decision, and sends nothing") {
        val blocked = Decision(DecisionAction.Block, 12, List("deny_list"))
        val env = Env(decision = blocked)
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <-
            env.service.prepareInitialOtp(authId, initialConversation, Right(Phone("+491512345678")), factorIndex = 0, proof = ProofInput.NoScript)
          steps = env.antifraud.steps
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          steps.map(_.outcome) == List(AntifraudOutcome.blocked),
          steps.flatMap(_.decision) == List(blocked),
          env.antifraud.smsAsked.isEmpty,
        )
      },
      test("a Fake decision sends nothing, is answered like a send and recorded as faked") {
        val fake = Decision(DecisionAction.Fake, 0, List("fake_list"))
        val env = Env(decision = fake)
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp.copy(real = None))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <-
            env.service.prepareInitialOtp(authId, initialConversation, Right(Phone("+491512345678")), factorIndex = 0, proof = ProofInput.NoScript)
          steps = env.antifraud.steps
        yield assertTrue(
          result.isInstanceOf[ConversationResult.RenderStep],
          steps.map(_.outcome) == List(AntifraudOutcome.faked),
          // A fake code is not charged to the range's budget.
          env.antifraud.smsAsked == List(false),
        )
      },
      test("a fake send waits as long as the antifraud sample says, a real one does not wait, and is timed") {
        val fake = Decision(DecisionAction.Fake, 0, Nil)
        def run(decision: Decision, otp: ConversationStep.Otp) =
          val env = Env(decision = decision, fakeDelay = 3.seconds)
          for
            _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
            _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
            _ <- env.otpService.prepareOtp.succeedsWith(otp)
            _ <- env.conversationRepository.overwrite.succeedsWith(true)
            _ <- env.otpService.sendOtp.succeedsWith(())
            fiber <- env.service.prepareInitialOtp(
              authId,
              initialConversation,
              Right(Phone("+491512345678")),
              factorIndex = 0,
              proof = ProofInput.NoScript,
            ).fork
            _ <- TestClock.adjust(2.seconds)
            early <- fiber.poll
            _ <- TestClock.adjust(2.seconds)
            _ <- fiber.join
          yield (early.isEmpty, env.antifraud.sends.size)
        for
          (fakeWaited, fakeTimed) <- run(fake, realOtp.copy(real = None))
          (realWaited, realTimed) <- run(Decision.allow, realOtp)
        yield assertTrue(fakeWaited, fakeTimed == 0, !realWaited, realTimed == 1)
      },
      test("a fake send waits for what is left of the sample after its own work, so that it is as slow as a real one, no slower") {
        val env = Env(decision = Decision(DecisionAction.Fake, 0, Nil), fakeDelay = 3.seconds)
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp.copy(real = None))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          // The work around the send takes 2 s of the 3 s sample.
          _ <- env.otpService.sendOtp.returnsZIO(_ => ZIO.sleep(2.seconds))
          fiber <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, ProofInput.NoScript).fork
          _ <- TestClock.adjust(2.seconds)
          early <- fiber.poll
          _ <- TestClock.adjust(900.millis)
          stillWaiting <- fiber.poll
          _ <- TestClock.adjust(100.millis)
          _ <- fiber.join
        yield assertTrue(early.isEmpty, stillWaiting.isEmpty)
      },
      test("a fake send whose own work took longer than the sample does not wait on top") {
        val env = Env(decision = Decision(DecisionAction.Fake, 0, Nil), fakeDelay = 1.second)
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp.copy(real = None))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.returnsZIO(_ => ZIO.sleep(2.seconds))
          fiber <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, ProofInput.NoScript).fork
          _ <- TestClock.adjust(2.seconds)
          done <- fiber.poll
        yield assertTrue(done.isDefined)
      },
      test("a code that is faked because the number is no user's waits just as long: the wait is for what is not sent, not for the decision") {
        val env = Env(fakeDelay = 3.seconds)
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp.copy(real = None))
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          fiber <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, ProofInput.NoScript).fork
          _ <- TestClock.adjust(2.seconds)
          early <- fiber.poll
          _ <- TestClock.adjust(2.seconds)
          _ <- fiber.join
        yield assertTrue(early.isEmpty, env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.suppressed))
      },
      test("in shadow a decision that would block, fake or challenge is acted on as Allow: a real code goes out, and the event carries both") {
        def run(would: DecisionAction) =
          val shadow = Decision(DecisionAction.Allow, 80, List("deny_list:ip"), would)
          val env = Env(decision = shadow)
          for
            _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
            _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
            _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
            _ <- env.conversationRepository.overwrite.succeedsWith(true)
            _ <- env.otpService.sendOtp.succeedsWith(())
            result <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, ProofInput.NoScript)
          yield (result, env, shadow)
        for
          (blockResult, blockEnv, blocked) <- run(DecisionAction.Block)
          (fakeResult, fakeEnv, faked) <- run(DecisionAction.Fake)
          (challengeResult, challengeEnv, challenged) <- run(DecisionAction.Challenge)
        yield assertTrue(
          List(blockResult, fakeResult, challengeResult).forall(_.isInstanceOf[ConversationResult.RenderStep]),
          List(blockResult, fakeResult, challengeResult).forall(_ != ConversationResult.RenderStep(ConversationStep.AccessDenied)),
          blockEnv.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.sent),
          blockEnv.antifraud.steps.flatMap(_.decision) == List(blocked),
          fakeEnv.antifraud.steps.flatMap(_.decision) == List(faked),
          challengeEnv.antifraud.steps.flatMap(_.decision) == List(challenged),
          // The code is real: nothing was told to make it fake.
          List(blockEnv, fakeEnv, challengeEnv).forall(_.otpService.prepareOtp.calls.forall(!_._5)),
          List(blockEnv, fakeEnv, challengeEnv).forall(_.otpService.sendOtp.calls.size == 1),
          blockEnv.antifraud.smsAsked == List(true),
        )
      },
      test("in shadow a number whose range the budget would have closed is sent a code, and the event says the budget would have refused it") {
        val env = Env(smsWouldRefuse = true)
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, ProofInput.NoScript)
          step = env.antifraud.steps.head
        yield assertTrue(
          result.isInstanceOf[ConversationResult.RenderStep],
          step.outcome == AntifraudOutcome.sent,
          step.decision.exists(d => d.action == DecisionAction.Allow && d.wouldAction == DecisionAction.Block && d.firedRules == List("sms_budget")),
          env.antifraud.smsReleased == 0,
          env.otpService.sendOtp.calls.size == 1,
        )
      },
      test("a code entered after a fake send is recorded as fake_verify") {
        val env = Env()
        val fakeStep = realOtp.copy(real = None)
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkOtp(initialConversation.copy(credential = Some(Left(email))), fakeStep, otpCode, authId)
        yield assertTrue(env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.fake_verify))
      },
    ),
    suite("alternatives to the code")(
      // A conversation asked for a code, whoever the number belongs to: nothing here depends on it.
      suite("a refused or faked send")(
        test(
          "a Block decision for a client whose flow can ask for a passkey is answered as a send, with the code page and nothing sent, and recorded as blocked",
        ) {
          val blocked = Decision(DecisionAction.Block, 90, List("deny_list"))
          val env = Env(decision = blocked)
          for
            _ <- passkeyTenant(env)
            result <- request(env, ProofInput.NoScript, conversation = knownUser)
            written = env.conversationRepository.overwrite.calls.map(_._2.step)
          yield assertTrue(
            result.isInstanceOf[ConversationResult.RenderStep],
            result != ConversationResult.RenderStep(ConversationStep.AccessDenied),
            // What the page shows is what a fake send shows: a code step, sent, with no code behind it.
            written.collect { case otp: ConversationStep.Otp => otp }.forall(otp => otp.isFake && otp.timesRequested == 1 && !otp.pending),
            env.otpService.prepareOtp.calls.forall(_._5),
            // The event is the decision that was taken, not a fake.
            env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.blocked),
            env.antifraud.steps.flatMap(_.decision) == List(blocked),
            // Nothing is charged to the range.
            env.antifraud.smsAsked == List(false),
          )
        },
        test("a Block decision gives the same code page whoever the number belongs to: a user, a registration, nobody") {
          val blocked = Decision(DecisionAction.Block, 90, List("deny_list"))
          def run(conversation: ConversationRecord) =
            val env = Env(decision = blocked)
            for
              _ <- passkeyTenant(env)
              result <- request(env, ProofInput.NoScript, conversation = conversation)
            yield (result, env)
          val registering = initialConversation.copy(
            authFlow = Alt.flowWithPasskey,
            registrationFlow = Some(RegistrationFlow(credential = RegistrationCredential.phone, steps = Nil, roleIds = Set.empty)),
            registrationStep = Some(0),
          )
          for
            (user, userEnv) <- run(knownUser)
            (nobody, nobodyEnv) <- run(initialConversation.copy(authFlow = Alt.flowWithPasskey))
            (registration, _) <- run(registering)
          yield assertTrue(
            user == nobody,
            user == registration,
            user != ConversationResult.RenderStep(ConversationStep.AccessDenied),
            userEnv.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.blocked),
            nobodyEnv.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.blocked),
          )
        },
        test(
          "a Block decision for a client whose flow cannot ask for a passkey stays the access page: nothing is added to a refusal that has nothing to offer",
        ) {
          val blocked = Decision(DecisionAction.Block, 90, List("deny_list"))
          val env = Env(decision = blocked)
          for
            _ <- passkeyTenant(env)
            result <- request(env, ProofInput.NoScript, conversation = knownUser.copy(authFlow = AuthFlow.default))
          yield assertTrue(
            result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
            env.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.blocked),
            env.otpService.sendOtp.calls.isEmpty,
          )
        },
        test("a Fake decision gives the same code page whoever the number belongs to: nothing about it differs") {
          val fake = Decision(DecisionAction.Fake, 0, List("fake_list"))
          def run(conversation: ConversationRecord) =
            val env = Env(decision = fake)
            for
              _ <- passkeyTenant(env)
              result <- request(env, ProofInput.NoScript, conversation = conversation)
            yield (result, env)
          for
            (user, userEnv) <- run(knownUser)
            (nobody, nobodyEnv) <- run(initialConversation.copy(authFlow = Alt.flowWithPasskey))
          yield assertTrue(
            user == nobody,
            user.isInstanceOf[ConversationResult.RenderStep],
            userEnv.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.faked),
            nobodyEnv.antifraud.steps.map(_.outcome) == List(AntifraudOutcome.faked),
            userEnv.otpService.prepareOtp.calls.forall(_._5),
          )
        },
      ),
    ),
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
          _ <- env.service.prepareInitialOtp(authId, conversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
          steps = env.antifraud.steps
        yield assertTrue(
          steps == List(
            RecordingAntifraudEvents.Step(
              AntifraudAction.otp_request,
              authId,
              clientId,
              Some("Mozilla/5.0"),
              Left(email),
              AntifraudOutcome.sent,
              None,
            ),
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
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
              _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
            yield env.antifraud.steps.map(_.outcome)
          }
          limited <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
              _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.RateLimited(30L))
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
            yield env.antifraud.steps.map(_.outcome)
          }
          submitBan <- {
            val env = Env()
            for
              _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Banned)
              _ <- env.conversationRepository.overwrite.succeedsWith(true)
              _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript).exit
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
            result <- env.service.prepareInitialOtp(
              authId,
              initialConversation,
              Right(versola.util.Phone("+4915123456789")),
              factorIndex = 0,
              proof = ProofInput.NoScript,
            )
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
          _ <- failing.service.prepareInitialOtp(
            authId,
            initialConversation,
            Right(versola.util.Phone("+4915123456789")),
            factorIndex = 0,
            proof = ProofInput.NoScript,
          ).exit
          _ <- lost.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- lost.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- lost.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- lost.conversationRepository.overwrite.succeedsWith(false)
          _ <- lost.service.prepareInitialOtp(
            authId,
            initialConversation,
            Right(versola.util.Phone("+4915123456789")),
            factorIndex = 0,
            proof = ProofInput.NoScript,
          )
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
          _ <- broken.service.prepareInitialOtp(
            authId,
            initialConversation,
            Right(versola.util.Phone("+4915123456789")),
            factorIndex = 0,
            proof = ProofInput.NoScript,
          ).exit
          _ <- interrupted.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- interrupted.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- interrupted.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- interrupted.conversationRepository.overwrite.returnsZIO(_ => ZIO.never)
          fiber <- interrupted.service
            .prepareInitialOtp(authId, initialConversation, Right(versola.util.Phone("+4915123456789")), factorIndex = 0, proof = ProofInput.NoScript)
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
            fiber <- env.service.prepareInitialOtp(authId, initialConversation, phone, factorIndex = 0, proof = ProofInput.NoScript).fork
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
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
        yield assertTrue(env.antifraud.smsAsked.isEmpty, env.antifraud.smsReleased == 0)
      },
      test("a request that lost the write race and sent nothing records nothing") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(false)
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
          result <- env.service.prepareInitialOtp(authId, resolvedConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
        yield assertTrue(
          result == ConversationResult.RenderStep(ConversationStep.AccessDenied),
          env.otpService.sendOtp.calls.isEmpty,
        )
      },
      test("throttle a step-up OTP request and its submit ban under the StepUp challenge types") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          _ <- env.service.prepareInitialOtp(authId, stepUp(initialConversation), Left(email), factorIndex = 0, proof = ProofInput.NoScript)
        yield assertTrue(
          env.submissionLimiter.statusFor.calls.map(_._3) == List(List(ChallengeType.StepUpOtpSubmit)),
          env.submissionLimiter.tryAcquire.calls.map(_._3) == List(ChallengeType.StepUpOtpRequest),
        )
      },
      test("a regular sign-in OTP stays on the OtpRequest and OtpSubmit challenge types") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
        yield assertTrue(
          env.submissionLimiter.statusFor.calls.map(_._3) == List(List(ChallengeType.OtpSubmit)),
          env.submissionLimiter.tryAcquire.calls.map(_._3) == List(ChallengeType.OtpRequest),
        )
      },
      test("return AccessDenied when banned on OTP submit (ban earned via wrong codes)") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.returnsZIO:
            case (_, _, types) if types.contains(ChallengeType.OtpSubmit) => ZIO.succeed(LimitStatus.Banned)
            case _ => ZIO.succeed(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
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
      test("throttle a wrong step-up code under StepUpOtpSubmit") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkOtp(stepUp(otpRecord), submittedOtp, otpCode, authId)
        yield assertTrue(
          env.submissionLimiter.isBanned.calls.map(_._3) == List(ChallengeType.StepUpOtpSubmit),
          env.submissionLimiter.recordLimit.calls.map(_._3) == List(ChallengeType.StepUpOtpSubmit),
        )
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
      test("a wrong code clears the ceremony and the passkey error a passkey asked from the page left, and keeps what was sent") {
        val env = Env()
        val asked = submittedOtp.copy(passkeyRequest = Some("ceremony"), passkeyFailed = true, lastSentAt = Some(Alt.now))
        val record = otpRecord.copy(step = asked)
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          result <- env.service.checkOtp(record, asked, OtpCode("000000"), authId)
        yield assertTrue(
          result == ConversationResult.RenderStep(asked.copy(timesSubmitted = 1, passkeyRequest = None, passkeyFailed = false)),
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
    suite("audit log")(
      test("a correct code records login.step success for the user; a wrong one failure wrong_code, and no code is in either") {
        val verified = Env()
        val wrong = Env()
        for
          _ <- verified.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- verified.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          _ <- verified.conversationRepository.overwrite.succeedsWith(true)
          _ <- verified.userRepository.markVerified.succeedsWith(Option.empty[ast.Json.Obj])
          _ <- verified.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
          _ <- wrong.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- wrong.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- wrong.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- wrong.conversationRepository.overwrite.succeedsWith(true)
          _ <- wrong.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
          a <- verified.audit.recorded
          b <- wrong.audit.recorded
        yield assertTrue(
          a.map(d => (d.kind, d.outcome, d.reason)) == List((AuthAuditKinds.LoginStep, AuditOutcome.success, None)),
          a.head.userId == Some(userId.asInstanceOf[java.util.UUID]),
          a.head.details == AuthAuditDetails.LoginStep(
            step = "otp",
            conversationId = authId.toString,
            kind = "login",
            subjectHash = None,
            afDecision = None,
          ),
          b.map(d => (d.outcome, d.reason)) == List((AuditOutcome.failure, Some("wrong_code"))),
          !a.head.details.toJson.toString.contains(otpCode),
        )
      },
      test("a fake code is answered as a wrong one in the log too: nothing says the code was never real") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkOtp(otpRecord, submittedOtp.copy(real = None), otpCode, authId)
          drafts <- env.audit.recorded
        yield assertTrue(drafts.map(_.reason) == List(Some("wrong_code")))
      },
      test("a locked destination records login.step denied locked, and a rate limited one denied throttled") {
        val banned = Env()
        val limited = Env()
        for
          _ <- banned.submissionLimiter.isBanned.succeedsWith(LimitStatus.Banned)
          _ <- banned.conversationRepository.overwrite.succeedsWith(true)
          _ <- banned.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
          _ <- limited.submissionLimiter.isBanned.succeedsWith(LimitStatus.RateLimited(30L))
          _ <- limited.conversationRepository.overwrite.succeedsWith(true)
          _ <- limited.service.checkOtp(otpRecord, submittedOtp, otpCode, authId)
          a <- banned.audit.recorded
          b <- limited.audit.recorded
        yield assertTrue(
          a.map(d => (d.outcome, d.reason)) == List((AuditOutcome.denied, Some("locked"))),
          b.map(d => (d.outcome, d.reason)) == List((AuditOutcome.denied, Some("throttled"))),
          banned.otpService.checkOtp.calls.isEmpty,
        )
      },
      test("with no user resolved yet the event carries the keyed hash of the credential, not the address") {
        val env = Env()
        for
          _ <- env.submissionLimiter.isBanned.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Failure)
          _ <- env.submissionLimiter.recordLimit.succeedsWith(LimitStatus.Allowed)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.service.checkOtp(otpRecord.copy(userId = None), submittedOtp, otpCode, authId)
          drafts <- env.audit.recorded
          details = drafts.map(_.details).collect { case d: AuthAuditDetails.LoginStep => d }
        yield assertTrue(drafts.head.userId.isEmpty, details.flatMap(_.subjectHash).nonEmpty)
      },
      test("a Block decision records login.step denied antifraud_block with the decision, and a Fake one antifraud_fake") {
        val blocked = Env(decision = Decision(DecisionAction.Block, 12, List("deny_list")))
        val fake = Env(decision = Decision(DecisionAction.Fake, 0, List("fake_list")))
        for
          _ <- blocked.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- blocked.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- blocked.conversationRepository.overwrite.succeedsWith(true)
          _ <- blocked.service.prepareInitialOtp(
            authId,
            initialConversation,
            Right(Phone("+491512345678")),
            factorIndex = 0,
            proof = ProofInput.NoScript,
          )
          _ <- fake.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- fake.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- fake.otpService.prepareOtp.succeedsWith(realOtp.copy(real = None))
          _ <- fake.conversationRepository.overwrite.succeedsWith(true)
          _ <- fake.otpService.sendOtp.succeedsWith(())
          _ <-
            fake.service.prepareInitialOtp(authId, initialConversation, Right(Phone("+491512345678")), factorIndex = 0, proof = ProofInput.NoScript)
          a <- blocked.audit.recorded
          b <- fake.audit.recorded
          blockedDetails = a.map(_.details).collect { case d: AuthAuditDetails.LoginStep => d }
          fakeDetails = b.map(_.details).collect { case d: AuthAuditDetails.LoginStep => d }
        yield assertTrue(
          a.map(d => (d.kind, d.outcome, d.reason)) == List((AuthAuditKinds.LoginStep, AuditOutcome.denied, Some("antifraud_block"))),
          blockedDetails.map(d => (d.step, d.afDecision)) == List(("otp_request", Some("Block"))),
          b.map(d => (d.outcome, d.reason)) == List((AuditOutcome.denied, Some("antifraud_fake"))),
          fakeDetails.flatMap(_.afDecision) == List("Fake"),
          // the phone number is in neither
          !(a ++ b).exists(_.details.toJson.toString.contains("491512345678")),
        )
      },
      test("a code the SMS budget refused is recorded as denied throttled") {
        val env = Env(smsAllowed = false)
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <-
            env.service.prepareInitialOtp(authId, initialConversation, Right(Phone("+4915123456789")), factorIndex = 0, proof = ProofInput.NoScript)
          drafts <- env.audit.recorded
        yield assertTrue(drafts.map(d => (d.outcome, d.reason)) == List((AuditOutcome.denied, Some("throttled"))))
      },
      test("a code that was sent, and a request that lost the write race, record no login.step: only judged steps are events") {
        val env = Env()
        for
          _ <- env.submissionLimiter.statusFor.succeedsWith(LimitStatus.Allowed)
          _ <- env.submissionLimiter.tryAcquire.succeedsWith(LimitStatus.Allowed)
          _ <- env.otpService.prepareOtp.succeedsWith(realOtp)
          _ <- env.conversationRepository.overwrite.succeedsWith(true)
          _ <- env.otpService.sendOtp.succeedsWith(())
          _ <- env.service.prepareInitialOtp(authId, initialConversation, Left(email), factorIndex = 0, proof = ProofInput.NoScript)
          drafts <- env.audit.recorded
        yield assertTrue(drafts.isEmpty)
      },
    ),
  )

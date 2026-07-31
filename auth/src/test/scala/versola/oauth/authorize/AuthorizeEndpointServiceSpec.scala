package versola.oauth.authorize

import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, Prompt, ResponseTypeEntry}
import versola.oauth.jwks.JwksService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFactor, AuthFactorType, AuthFlow, AuthMethodRef, ClientId, OAuthClientRecord, PassedAuthFactor, PassedFactorRecord, PrimaryAuthFlow, PrimaryCredential, ScopeToken, TenantId}
import versola.oauth.conversation.{ConversationRepository, ConversationResult, ConversationRouter, EmailSubmission, PhoneSubmission}
import versola.oauth.model.{AccessToken, AuthorizationCode, CodeChallenge, CodeChallengeMethod, State}
import versola.oauth.session.SessionService
import versola.oauth.session.model.{PublicSessionId, SessionId, SessionInfo, SessionRecord, UserAgentInfo}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.user.model.UserRecord
import versola.util.{AuthPropertyGenerator, Email, MAC, Phone, Secret, SecureRandom, SecurityService, UnitSpecBase}
import zio.{ZIO, Exit}
import zio.http.URL
import zio.prelude.{NonEmptyList, NonEmptySet}
import zio.*
import zio.test.*


import java.time.Instant
import java.util.UUID
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}

object AuthorizeEndpointServiceSpec extends UnitSpecBase:

  val clientId = ClientId("test-client")
  val redirectUri = URL.decode("https://example.com/callback").toOption.get
  val codeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")

  val otpFlow = AuthFlow(
    primary = PrimaryAuthFlow(
      credentials = List(PrimaryCredential.phone),
      inlinePassword = false,
      factors = List(AuthFactor(`type` = AuthFactorType.otp, required = true)),
    ),
    passkey = None,
    equivalents = Map.empty,
  )

  val clientWithOtpFlow = OAuthClientRecord(
    id = clientId,
    tenantId = TenantId("default"),
    clientName = "Test Client",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("openid")),
    externalAudience = List.empty,
    secret = None,
    previousSecret = None,
    accessTokenTtl = zio.durationInt(10).minutes,
    refreshTokenTtl = zio.durationInt(7776000).seconds,
    theme = "default",
    authFlow = Some(otpFlow),
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
  )

  val baseRequest = AuthorizeRequest(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = Set(ScopeToken("openid")),
    state = Some(State("state")),
    codeChallenge = codeChallenge,
    codeChallengeMethod = CodeChallengeMethod.S256,
    responseType = NonEmptySet(ResponseTypeEntry.Code),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    userAgent = None,
    prompt = Set.empty,
    maxAge = None,
    acrValues = None,
    sessionId = None,
    loginHint = None,
    idTokenHint = None,
  )

  val rawSessionId = SessionId(Array.fill(32)(5.toByte))
  val sessionMac = MAC(Array.fill(32)(1.toByte))
  val publicSessionId = PublicSessionId("test-public-session")
  val now = Instant.now()

  def sessionWithAmr(amr: Map[PassedAuthFactor, PassedFactorRecord]) = SessionRecord(
    userId = versola.user.model.UserId(UUID.randomUUID()),
    clientId = clientId,
    userAgent = UserAgentInfo.parse(None),
    createdAt = now,
    amr = amr,
    publicId = publicSessionId,
  )

  class Env:
    val conversationRepository = stub[ConversationRepository]
    val configurationService = stub[OAuthConfigurationService]
    val secureRandom = stub[SecureRandom]
    val config = TestEnvConfig.coreConfig
    val securityService = stub[SecurityService]
    val sessionService = stub[SessionService]
    val authPropertyGenerator = stub[AuthPropertyGenerator]
    val authorizationCodeRepository = stub[AuthorizationCodeRepository]
    val userRepository = stub[UserRepository]
    val userInfoService = stub[UserInfoService]
    val jwksService = TestEnvConfig.jwksService
    val conversationRouter = stub[ConversationRouter]
    val acrResolver = stub[AcrResolutionService]
    val service = AuthorizeEndpointService.Impl(
      conversationRepository,
      configurationService,
      secureRandom,
      config,
      securityService,
      sessionService,
      authPropertyGenerator,
      authorizationCodeRepository,
      userRepository,
      userInfoService,
      jwksService,
      conversationRouter,
      acrResolver,
    )

  val phoneHint = Phone("+12025551234")
  val emailHint = Email("user@example.com")

  val passwordFlow = AuthFlow(
    primary = PrimaryAuthFlow(
      credentials = List(PrimaryCredential.email),
      inlinePassword = false,
      factors = List(AuthFactor(`type` = AuthFactorType.password, required = true)),
    ),
    passkey = None,
    equivalents = Map.empty,
  )

  val clientWithPasswordFlow = clientWithOtpFlow.copy(authFlow = Some(passwordFlow))

  val dummyConversation = versola.oauth.conversation.model.ConversationRecord(
    clientId = clientId,
    redirectUri = redirectUri,
    scope = Set(ScopeToken("openid")),
    codeChallenge = codeChallenge,
    codeChallengeMethod = CodeChallengeMethod.S256,
    state = Some(State("state")),
    userId = None,
    credential = None,
    step = versola.oauth.conversation.model.ConversationStep.Credential(
      primaryCredentials = List(versola.oauth.client.model.PrimaryCredential.email),
      inlinePassword = false,
      passkey = false,
    ),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = NonEmptySet(ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
    authFlow = otpFlow,
    userAgent = None,
    version = 0,
    amr = Map.empty,
    needsPasswordChange = false,
    targetAcr = None,

    priorSessionId = None,
  )

  val spec = suite("AuthorizeEndpointService")(
    test("create new conversation when no session") {
      val env = Env()
      val uuid = UUID.randomUUID()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest)
      yield assertTrue(result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)))
    },
    test("fail with LoginRequired when no session and prompt=none") {
      val env = Env()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        result <- env.service.authorize(baseRequest.copy(prompt = Set(Prompt.none))).exit
      yield assertTrue(result.isFailure)
    },
    test("create new conversation when session cookie present but session not found in db") {
      val env = Env()
      val uuid = UUID.randomUUID()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(None)
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId)))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.amr.isEmpty,
      )
    },
    test("fail with LoginRequired when session cookie present but session not found in db and prompt=none") {
      val env = Env()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(None)
        result <- env.service.authorize(
          baseRequest.copy(sessionId = Some(rawSessionId), prompt = Set(Prompt.none))
        ).exit
      yield assertTrue(result.isFailure)
    },
    test("silently authorize when session amr satisfies all required factors") {
      val env = Env()
      val code = AuthorizationCode(Array.fill(16)(3.toByte))
      val accessToken = AccessToken(Array.fill(16)(4.toByte))
      val codeMac = MAC(Array.fill(32)(2.toByte))
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.configurationService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
        _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(code)
        _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
        _ <- env.securityService.mac.succeedsWith(codeMac)
        _ <- env.authorizationCodeRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId)))
        createCalls = env.authorizationCodeRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Authorized(code, None),
        createCalls.head._2.amr == AuthMethodRef.amrClaim(session.amr),
        createCalls.head._2.authTime == session.createdAt,
      )
    },
    test("fail with AccessDenied when hybrid silent auth has session but missing user") {
      val env = Env()
      val code = AuthorizationCode(Array.fill(16)(3.toByte))
      val accessToken = AccessToken(Array.fill(16)(4.toByte))
      val codeMac = MAC(Array.fill(32)(2.toByte))
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      val hybridRequest = baseRequest.copy(
        sessionId = Some(rawSessionId),
        responseType = NonEmptySet(ResponseTypeEntry.Code, ResponseTypeEntry.IdToken),
      )
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.configurationService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
        _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(code)
        _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
        _ <- env.securityService.mac.succeedsWith(codeMac)
        _ <- env.authorizationCodeRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(None)
        result <- env.service.authorize(hybridRequest).flip
      yield assertTrue(result == Error.AccessDenied(redirectUri, baseRequest.state))
    },
    test("silently authorize when passkey satisfies otp via equivalents") {
      val env = Env()
      val flowWithEquivalents = otpFlow.copy(equivalents = Map(PassedAuthFactor.passkey -> Set(PassedAuthFactor.otp)))
      val clientWithEquivalents = clientWithOtpFlow.copy(authFlow = Some(flowWithEquivalents))
      val code = AuthorizationCode(Array.fill(16)(3.toByte))
      val accessToken = AccessToken(Array.fill(16)(4.toByte))
      val codeMac = MAC(Array.fill(32)(2.toByte))
      val session = sessionWithAmr(Map(PassedAuthFactor.passkey -> PassedFactorRecord(now, Set(AuthMethodRef.swk, AuthMethodRef.user, AuthMethodRef.mfa))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithEquivalents))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.configurationService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
        _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(code)
        _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
        _ <- env.securityService.mac.succeedsWith(codeMac)
        _ <- env.authorizationCodeRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId)))
      yield assertTrue(result == AuthorizeResponse.Authorized(code, None))
    },
    test("force re-authentication when prompt=login even if session amr satisfies all required factors") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), prompt = Set(Prompt.login)))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.amr.isEmpty,
      )
    },
    test("not bind conversation to session user when prompt=login forces re-authentication") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), prompt = Set(Prompt.login)))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
      )
    },
    test("fail with LoginRequired when session found but not satisfied and prompt=none") {
      val env = Env()
      val session = sessionWithAmr(Map.empty)
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), prompt = Set(Prompt.none))).exit
      yield assertTrue(result.isFailure)
    },
    test("create conversation seeded with session amr when factors not satisfied") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val passkeySeedAmr = Map(PassedAuthFactor.passkey -> PassedFactorRecord(now, Set(AuthMethodRef.swk, AuthMethodRef.user, AuthMethodRef.mfa)))
      val session = sessionWithAmr(passkeySeedAmr)
      // User has a phone — the otpFlow uses phone as primary credential, so the credential step
      // can be bypassed and advance() jumps straight to the missing OTP factor.
      val phone = Phone("+12025551234")
      val user = UserRecord.empty(session.userId).copy(phone = Some(phone))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.userRepository.find.succeedsWith(Some(user))
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId)))
        createCalls = env.conversationRepository.create.calls
        advanceTimes = env.conversationRouter.advance.times
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.amr == passkeySeedAmr,
        createCalls.head._2.userId == Some(session.userId),
        createCalls.head._2.credential == Some(Right(phone)),
        advanceTimes == 1,
      )
    },
    test("advance conversation to password step when login_hint email is provided on email+password flow") {
      val env = Env()
      val uuid = UUID.randomUUID()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithPasswordFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.conversationRouter.submit.succeedsWith((ConversationResult.IllegalState, dummyConversation))
        result <- env.service.authorize(baseRequest.copy(loginHint = Some(Left(emailHint))))
        submitCalls = env.conversationRouter.submit.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        submitCalls.nonEmpty,
        submitCalls.head._2 == EmailSubmission(emailHint),
      )
    },
    test("advance conversation to OTP step when login_hint phone is provided on phone+otp flow") {
      val env = Env()
      val uuid = UUID.randomUUID()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.conversationRouter.submit.succeedsWith((ConversationResult.IllegalState, dummyConversation))
        result <- env.service.authorize(baseRequest.copy(loginHint = Some(Right(phoneHint))))
        submitCalls = env.conversationRouter.submit.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        submitCalls.nonEmpty,
        submitCalls.head._2 == PhoneSubmission(phoneHint),
      )
    },
    test("force re-authentication when max_age exceeded") {
      val env = Env()
      val uuid = UUID.randomUUID()
      // Test clock is at Instant.EPOCH; session created 1 second before → exceeded maxAge=0
      val sessionUserId = versola.user.model.UserId(UUID.randomUUID())
      val oldSession = SessionRecord(
        userId = sessionUserId,
        clientId = clientId,
        userAgent = UserAgentInfo.parse(None),
        createdAt = Instant.EPOCH.minusSeconds(1),
        amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(Instant.EPOCH.minusSeconds(1), Set(AuthMethodRef.otp))),
        publicId = publicSessionId,
      )
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, oldSession)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(sessionUserId)))
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), maxAge = Some(0)))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
      )
    },
    test("fail with LoginRequired when max_age exceeded and prompt=none") {
      val env = Env()
      val oldSession = SessionRecord(
        userId = versola.user.model.UserId(UUID.randomUUID()),
        clientId = clientId,
        userAgent = UserAgentInfo.parse(None),
        createdAt = Instant.EPOCH.minusSeconds(1),
        amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(Instant.EPOCH.minusSeconds(1), Set(AuthMethodRef.otp))),
        publicId = publicSessionId,
      )
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, oldSession)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        result <- env.service.authorize(
          baseRequest.copy(sessionId = Some(rawSessionId), maxAge = Some(0), prompt = Set(Prompt.none))
        ).exit
      yield assertTrue(result.isFailure)
    },
    test("silently authorize when max_age not exceeded") {
      val env = Env()
      val code = AuthorizationCode(Array.fill(16)(3.toByte))
      val accessToken = AccessToken(Array.fill(16)(4.toByte))
      val codeMac = MAC(Array.fill(32)(2.toByte))
      // Test clock is at Instant.EPOCH; session also at EPOCH → not before EPOCH - 3600
      val freshSession = SessionRecord(
        userId = versola.user.model.UserId(UUID.randomUUID()),
        clientId = clientId,
        userAgent = UserAgentInfo.parse(None),
        createdAt = Instant.EPOCH,
        amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.otp))),
        publicId = publicSessionId,
      )
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, freshSession)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.configurationService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
        _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(code)
        _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
        _ <- env.securityService.mac.succeedsWith(codeMac)
        _ <- env.authorizationCodeRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), maxAge = Some(3600)))
      yield assertTrue(result == AuthorizeResponse.Authorized(code, None))
    },
    test("force re-authentication when acr_values requires mfa but session has single factor") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map(Acr("mfa") -> NonEmptyList(PassedAuthFactor.password, PassedAuthFactor.otp)))
        _ <- env.acrResolver.checkAcrSatisfaction.succeedsWith(None)
        _ <- env.acrResolver.resolveAchievableAcr.succeedsWith(Some(Acr("mfa")))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(session.userId)))
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), acrValues = Some(NonEmptyList(Acr("mfa")))))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
      )
    },
    test("fail with LoginRequired when acr_values not satisfied and prompt=none") {
      val env = Env()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.acrResolver.checkAcrSatisfaction.succeedsWith(None)
        result <- env.service.authorize(
          baseRequest.copy(sessionId = Some(rawSessionId), acrValues = Some(NonEmptyList(Acr("mfa"))), prompt = Set(Prompt.none))
        ).exit
      yield assertTrue(result.isFailure)
    },
    test("silently authorize when acr_values satisfied by session") {
      val env = Env()
      val code = AuthorizationCode(Array.fill(16)(3.toByte))
      val accessToken = AccessToken(Array.fill(16)(4.toByte))
      val codeMac = MAC(Array.fill(32)(2.toByte))
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map(Acr("otp") -> NonEmptyList(PassedAuthFactor.otp)))
        _ <- env.acrResolver.checkAcrSatisfaction.succeedsWith(Some(Acr("otp")))
        _ <- env.configurationService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
        _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(code)
        _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
        _ <- env.securityService.mac.succeedsWith(codeMac)
        _ <- env.authorizationCodeRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), acrValues = Some(NonEmptyList(Acr("otp")))))
      yield assertTrue(result == AuthorizeResponse.Authorized(code, None))
    },
    test("force re-authentication when tenant vocabulary ACR not satisfied") {
      val env = Env()
      val uuid = UUID.randomUUID()
      // "company_mfa" maps to internal "mfa" (requires 2+ factors); session has only otp
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map(Acr("company_mfa") -> NonEmptyList(PassedAuthFactor.password, PassedAuthFactor.otp)))
        _ <- env.acrResolver.checkAcrSatisfaction.succeedsWith(None)
        _ <- env.acrResolver.resolveAchievableAcr.succeedsWith(Some(Acr("company_mfa")))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(session.userId)))
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), acrValues = Some(NonEmptyList(Acr("company_mfa")))))
      yield assertTrue(result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)))
    },
    test("populate credential and user fields from found user during step-up") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      val user = UserRecord.empty(session.userId).copy(email = Some(emailHint))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map(Acr("mfa") -> NonEmptyList(PassedAuthFactor.password, PassedAuthFactor.otp)))
        _ <- env.acrResolver.checkAcrSatisfaction.succeedsWith(None)
        _ <- env.acrResolver.resolveAchievableAcr.succeedsWith(Some(Acr("mfa")))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(user))
        _ <- env.conversationRouter.advance.succeedsWith(())
        _ <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), acrValues = Some(NonEmptyList(Acr("mfa")))))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        createCalls.nonEmpty,
        createCalls.head._2.credential == Some(Left(emailHint)),
        createCalls.head._2.userEmail == Some(emailHint),
        createCalls.head._2.userId == Some(session.userId),
      )
    },
    test("silently authorize when tenant vocabulary ACR satisfied") {
      val env = Env()
      val code = AuthorizationCode(Array.fill(16)(3.toByte))
      val accessToken = AccessToken(Array.fill(16)(4.toByte))
      val codeMac = MAC(Array.fill(32)(2.toByte))
      // "company_otp" maps to internal "otp"; session has otp
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map(Acr("company_otp") -> NonEmptyList(PassedAuthFactor.otp)))
        _ <- env.acrResolver.checkAcrSatisfaction.succeedsWith(Some(Acr("company_otp")))
        _ <- env.configurationService.getSessionIdleTtl.succeedsWith(Option.empty[zio.Duration])
        _ <- env.authPropertyGenerator.nextAuthorizationCode.succeedsWith(code)
        _ <- env.authPropertyGenerator.nextAccessToken.succeedsWith(accessToken)
        _ <- env.securityService.mac.succeedsWith(codeMac)
        _ <- env.authorizationCodeRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), acrValues = Some(NonEmptyList(Acr("company_otp")))))
      yield assertTrue(result == AuthorizeResponse.Authorized(code, None))
    },
    test("force re-authentication when id_token_hint subject differs from session userId") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      val differentUserId = versola.user.model.UserId(UUID.randomUUID())
      val differentSub = differentUserId.toString
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val claims = new JWTClaimsSet.Builder()
        .subject(differentSub)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .build()
      val jwtToken = new SignedJWT(header, claims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map(Acr("mfa") -> NonEmptyList(PassedAuthFactor.password, PassedAuthFactor.otp)))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(differentUserId)))
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), idTokenHint = Some(idTokenHintStr)))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.amr.isEmpty,
      )
    },
    test("fail with AccessDenied when no achievable ACR can be resolved") {
      val env = Env()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.acrResolver.resolveAchievableAcr.succeedsWith(None)
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), acrValues = Some(NonEmptyList(Acr("completely_unknown_acr"))))).exit
      yield assertTrue(result.isFailure)
    },
    test("accept expired id_token_hint and force re-authentication when subject differs") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      val differentUserId = versola.user.model.UserId(UUID.randomUUID())
      val differentSub = differentUserId.toString
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val claims = new JWTClaimsSet.Builder()
        .subject(differentSub)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .expirationTime(new java.util.Date(0L))
        .build()
      val jwtToken = new SignedJWT(header, claims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(differentUserId)))
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), idTokenHint = Some(idTokenHintStr)))
      yield assertTrue(result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)))
    },
    // ── id_token_hint without an active session ──────────────────────────────
    test("fail with AccessDenied when id_token_hint is provided without session and ACR not achievable") {
      val env = Env()
      val hintUserId = versola.user.model.UserId(UUID.randomUUID())
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val hintClaims = new JWTClaimsSet.Builder()
        .subject(hintUserId.toString)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .build()
      val jwtToken = new SignedJWT(header, hintClaims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.acrResolver.resolveAchievableAcr.succeedsWith(None)
        result <- env.service.authorize(
          baseRequest.copy(idTokenHint = Some(idTokenHintStr), acrValues = Some(NonEmptyList(Acr("mfa"))))
        ).exit
      yield assertTrue(result.isFailure)
    },
    test("create step-up conversation when id_token_hint provided without session and ACR is achievable") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val hintUserId = versola.user.model.UserId(UUID.randomUUID())
      val targetAcr = Acr("mfa")
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val hintClaims = new JWTClaimsSet.Builder()
        .subject(hintUserId.toString)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .build()
      val jwtToken = new SignedJWT(header, hintClaims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.acrResolver.resolveAchievableAcr.succeedsWith(Some(targetAcr))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(hintUserId)))
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(
          baseRequest.copy(idTokenHint = Some(idTokenHintStr), acrValues = Some(NonEmptyList(targetAcr)))
        )
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.userId == Some(hintUserId),
        createCalls.head._2.targetAcr == Some(targetAcr),
      )
    },
    test("create conversation with known userId when id_token_hint provided without session and no acr_values") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val hintUserId = versola.user.model.UserId(UUID.randomUUID())
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val hintClaims = new JWTClaimsSet.Builder()
        .subject(hintUserId.toString)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .build()
      val jwtToken = new SignedJWT(header, hintClaims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(hintUserId)))
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(idTokenHint = Some(idTokenHintStr)))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.userId == Some(hintUserId),
      )
    },
    // ── forceReauth + acrValues path ─────────────────────────────────────────
    test("fail with AccessDenied when forceReauth and ACR not achievable for the known user") {
      val env = Env()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      val differentUserId = versola.user.model.UserId(UUID.randomUUID())
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val hintClaims = new JWTClaimsSet.Builder()
        .subject(differentUserId.toString)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .build()
      val jwtToken = new SignedJWT(header, hintClaims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.acrResolver.checkAcrSatisfaction.succeedsWith(None)
        _ <- env.acrResolver.resolveAchievableAcr.succeedsWith(None)
        result <- env.service.authorize(
          baseRequest.copy(
            sessionId = Some(rawSessionId),
            idTokenHint = Some(idTokenHintStr),
            acrValues = Some(NonEmptyList(Acr("mfa"))),
          )
        ).exit
      yield assertTrue(result.isFailure)
    },
    test("create step-up conversation when forceReauth and ACR achievable for the known user") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      val differentUserId = versola.user.model.UserId(UUID.randomUUID())
      val targetAcr = Acr("mfa")
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val hintClaims = new JWTClaimsSet.Builder()
        .subject(differentUserId.toString)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .build()
      val jwtToken = new SignedJWT(header, hintClaims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.acrResolver.checkAcrSatisfaction.succeedsWith(None)
        _ <- env.acrResolver.resolveAchievableAcr.succeedsWith(Some(targetAcr))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(UserRecord.empty(differentUserId)))
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(
          baseRequest.copy(
            sessionId = Some(rawSessionId),
            idTokenHint = Some(idTokenHintStr),
            acrValues = Some(NonEmptyList(targetAcr)),
          )
        )
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.userId == Some(differentUserId),
        createCalls.head._2.targetAcr == Some(targetAcr),
      )
    },
    // ── missing user: deny (step-up / re-verify) vs fallback (fresh hint / switch) ────────────
    test("fail with AccessDenied when factors not satisfied and session user no longer exists") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map.empty)
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.userRepository.find.succeedsWith(None)
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId))).flip
      yield assertTrue(result == Error.AccessDenied(redirectUri, baseRequest.state))
    },
    test("fail with AccessDenied when max_age forces re-auth and session user no longer exists") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val sessionUserId = versola.user.model.UserId(UUID.randomUUID())
      val oldSession = SessionRecord(
        userId = sessionUserId,
        clientId = clientId,
        userAgent = UserAgentInfo.parse(None),
        createdAt = Instant.EPOCH.minusSeconds(1),
        amr = Map(PassedAuthFactor.otp -> PassedFactorRecord(Instant.EPOCH.minusSeconds(1), Set(AuthMethodRef.otp))),
        publicId = publicSessionId,
      )
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, oldSession)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.userRepository.find.succeedsWith(None)
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), maxAge = Some(0))).flip
      yield assertTrue(result == Error.AccessDenied(redirectUri, baseRequest.state))
    },
    test("fall back to credential entry when id_token_hint user no longer exists") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val hintUserId = versola.user.model.UserId(UUID.randomUUID())
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val hintClaims = new JWTClaimsSet.Builder()
        .subject(hintUserId.toString)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .build()
      val jwtToken = new SignedJWT(header, hintClaims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(None)
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(idTokenHint = Some(idTokenHintStr)))
        createCalls = env.conversationRepository.create.calls
        advanceCalls = env.conversationRouter.advance.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.userId == None,
        advanceCalls.isEmpty,
      )
    },
    test("fall back to credential entry when forceReauth switches to a user that no longer exists") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      val differentUserId = versola.user.model.UserId(UUID.randomUUID())
      val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test-key-id")
        .`type`(JOSEObjectType.JWT)
        .build()
      val hintClaims = new JWTClaimsSet.Builder()
        .subject(differentUserId.toString)
        .audience(clientId.toString)
        .issuer("https://versolauth.com")
        .build()
      val jwtToken = new SignedJWT(header, hintClaims)
      jwtToken.sign(new RSASSASigner(TestEnvConfig.privateKey))
      val idTokenHintStr = jwtToken.serialize()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.configurationService.getAuthConversationTtl.succeedsWith(zio.Duration.fromSeconds(900))
        _ <- env.sessionService.find.succeedsWith(Some(SessionInfo(sessionMac, session)))
        _ <- env.configurationService.getAcrVocabulary.succeedsWith(Map.empty)
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(None)
        _ <- env.conversationRouter.advance.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), idTokenHint = Some(idTokenHintStr)))
        createCalls = env.conversationRepository.create.calls
        advanceCalls = env.conversationRouter.advance.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.userId == None,
        advanceCalls.isEmpty,
      )
    },

  )
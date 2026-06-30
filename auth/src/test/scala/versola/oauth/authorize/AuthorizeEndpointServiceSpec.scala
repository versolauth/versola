package versola.oauth.authorize

import versola.auth.TestEnvConfig
import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, Prompt, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFactor, AuthFactorType, AuthFlow, AuthMethodRef, ClientId, OAuthClientRecord, PassedAuthFactor, PassedFactorRecord, PrimaryAuthFlow, PrimaryCredential, ScopeToken, TenantId}
import versola.oauth.conversation.{ConversationRepository, ConversationResult, ConversationRouter, EmailSubmission}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.model.{AccessToken, AuthorizationCode, CodeChallenge, CodeChallengeMethod, State}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{SessionId, SessionRecord, UserAgentInfo}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.{AuthPropertyGenerator, JWT, MAC, Secret, SecureRandom, SecurityService, UnitSpecBase}
import zio.http.URL
import zio.json.ast.Json
import zio.prelude.NonEmptySet
import zio.test.*
import zio.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.UUID

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
    idTokenHint = None,
  )

  val rawSessionId = SessionId(Array.fill(32)(5.toByte))
  val sessionMac = MAC(Array.fill(32)(1.toByte))
  val now = Instant.now()

  def sessionWithAmr(amr: Map[PassedAuthFactor, PassedFactorRecord]) = SessionRecord(
    userId = versola.user.model.UserId(UUID.randomUUID()),
    clientId = clientId,
    userAgent = UserAgentInfo.parse(None),
    createdAt = now,
    amr = amr,
  )

  // Bad key pair for signature failure tests
  private val badKeyPairGenerator = KeyPairGenerator.getInstance("RSA")
  badKeyPairGenerator.initialize(2048)
  private val badPrivateKey = badKeyPairGenerator.generateKeyPair().getPrivate.asInstanceOf[RSAPrivateKey]

  class Env:
    val conversationRepository = stub[ConversationRepository]
    val configurationService = stub[OAuthConfigurationService]
    val secureRandom = stub[SecureRandom]
    val config = TestEnvConfig.coreConfig
    val securityService = stub[SecurityService]
    val sessionRepository = stub[SessionRepository]
    val authPropertyGenerator = stub[AuthPropertyGenerator]
    val authorizationCodeRepository = stub[AuthorizationCodeRepository]
    val userRepository = stub[UserRepository]
    val userInfoService = stub[UserInfoService]
    val conversationRouter = stub[ConversationRouter]
    val service = AuthorizeEndpointService.Impl(
      conversationRepository,
      configurationService,
      secureRandom,
      config,
      securityService,
      sessionRepository,
      authPropertyGenerator,
      authorizationCodeRepository,
      userRepository,
      userInfoService,
      conversationRouter,
    )

  // Helpers for id_token_hint tests
  val hintUserId = UserId(UUID.randomUUID())
  val hintUser = UserRecord(
    id = hintUserId,
    email = Some(versola.util.Email("hint@example.com")),
    phone = None,
    login = None,
    claims = Json.Obj(),
    uiLocales = None,
  )

  def makeIdToken(
      subject: UserId,
      audience: String,
      privateKey: java.security.PrivateKey,
      keyId: String = "test-key-id",
      ttl: zio.Duration = 1.hour,
  ): zio.Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = TestEnvConfig.jwtConfig.issuer,
        subject = subject.toString,
        audience = List(audience),
        custom = Json.Obj(),
      ),
      ttl = ttl,
      signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, keyId, privateKey),
    )

  val spec = suite("AuthorizeEndpointService")(
    test("create new conversation when no session") {
      val env = Env()
      val uuid = UUID.randomUUID()
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
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
    test("silently authorize when session amr satisfies all required factors") {
      val env = Env()
      val code = AuthorizationCode(Array.fill(16)(3.toByte))
      val accessToken = AccessToken(Array.fill(16)(4.toByte))
      val codeMac = MAC(Array.fill(32)(2.toByte))
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.securityService.mac.succeedsWith(sessionMac)
        _ <- env.sessionRepository.find.succeedsWith(Some(session))
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
        _ <- env.securityService.mac.succeedsWith(sessionMac)
        _ <- env.sessionRepository.find.succeedsWith(Some(session))
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
        _ <- env.securityService.mac.succeedsWith(sessionMac)
        _ <- env.sessionRepository.find.succeedsWith(Some(session))
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
        _ <- env.securityService.mac.succeedsWith(sessionMac)
        _ <- env.sessionRepository.find.succeedsWith(Some(session))
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
    test("fail with LoginRequired when session found but not satisfied and prompt=none") {
      val env = Env()
      val session = sessionWithAmr(Map.empty)
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.securityService.mac.succeedsWith(sessionMac)
        _ <- env.sessionRepository.find.succeedsWith(Some(session))
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), prompt = Set(Prompt.none))).exit
      yield assertTrue(result.isFailure)
    },
    idTokenHintTests,
    test("create conversation seeded with session amr when factors not satisfied") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val passkeySeedAmr = Map(PassedAuthFactor.passkey -> PassedFactorRecord(now, Set(AuthMethodRef.swk, AuthMethodRef.user, AuthMethodRef.mfa)))
      val session = sessionWithAmr(passkeySeedAmr)
      for
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.securityService.mac.succeedsWith(sessionMac)
        _ <- env.sessionRepository.find.succeedsWith(Some(session))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId)))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(versola.oauth.conversation.model.AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.amr == passkeySeedAmr,
      )
    },
  )

  def idTokenHintTests = suite("id_token_hint")(
    test("valid hint with no session skips credential screen and starts OTP") {
      val env = Env()
      val uuid = UUID.randomUUID()
      for
        hint <- makeIdToken(hintUserId, clientId.toString, TestEnvConfig.privateKey)
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(hintUser))
        _ <- env.conversationRouter.submit.succeedsWith(ConversationResult.RenderStep(ConversationStep.Credential(Nil, false, false)))
        result <- env.service.authorize(baseRequest.copy(idTokenHint = Some(hint)))
        createCalls = env.conversationRepository.create.calls
        submitCalls = env.conversationRouter.submit.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(AuthId(uuid)),
        createCalls.nonEmpty,
        createCalls.head._2.expectedUserId == Some(hintUserId),
        submitCalls.length == 1,
        submitCalls.head._2 == EmailSubmission(hintUser.email.get),
      )
    },
    test("hint with bad signature fails with IdTokenHintInvalid") {
      val env = Env()
      for
        hint <- makeIdToken(hintUserId, clientId.toString, badPrivateKey)
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        result <- env.service.authorize(baseRequest.copy(idTokenHint = Some(hint))).exit
      yield assertTrue(
        result.isFailure,
        result.causeOption.exists(_.failureOption.exists {
          case Error.IdTokenHintInvalid(_, _) => true
          case _ => false
        }),
      )
    },
    test("expired hint with valid signature is accepted") {
      val env = Env()
      val uuid = UUID.randomUUID()
      for
        hint <- makeIdToken(hintUserId, clientId.toString, TestEnvConfig.privateKey, ttl = 1.second)
        _ <- TestClock.adjust(2.seconds)
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(hintUser))
        _ <- env.conversationRouter.submit.succeedsWith(ConversationResult.RenderStep(ConversationStep.Credential(Nil, false, false)))
        result <- env.service.authorize(baseRequest.copy(idTokenHint = Some(hint)))
      yield assertTrue(result == AuthorizeResponse.Initialize(AuthId(uuid)))
    },
    test("hint with wrong issuer fails with IdTokenHintInvalid") {
      val env = Env()
      for
        hint <- JWT.serialize(
          claims = JWT.Claims(
            issuer = "https://wrong-issuer.com",
            subject = hintUserId.toString,
            audience = List(clientId.toString),
            custom = Json.Obj(),
          ),
          ttl = 1.hour,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "test-key-id", TestEnvConfig.privateKey),
        )
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        result <- env.service.authorize(baseRequest.copy(idTokenHint = Some(hint))).exit
      yield assertTrue(
        result.isFailure,
        result.causeOption.exists(_.failureOption.exists {
          case Error.IdTokenHintInvalid(_, _) => true
          case _ => false
        }),
      )
    },
    test("hint with wrong audience fails with IdTokenHintInvalid") {
      val env = Env()
      for
        hint <- makeIdToken(hintUserId, "other-client", TestEnvConfig.privateKey)
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        result <- env.service.authorize(baseRequest.copy(idTokenHint = Some(hint))).exit
      yield assertTrue(
        result.isFailure,
        result.causeOption.exists(_.failureOption.exists {
          case Error.IdTokenHintInvalid(_, _) => true
          case _ => false
        }),
      )
    },
    test("hint for different user than active session forces re-authentication") {
      val env = Env()
      val uuid = UUID.randomUUID()
      val differentUserId = UserId(UUID.randomUUID())
      val session = sessionWithAmr(Map(PassedAuthFactor.otp -> PassedFactorRecord(now, Set(AuthMethodRef.otp))))
        .copy(userId = differentUserId)
      for
        hint <- makeIdToken(hintUserId, clientId.toString, TestEnvConfig.privateKey)
        _ <- env.configurationService.find.succeedsWith(Some(clientWithOtpFlow))
        _ <- env.securityService.mac.succeedsWith(sessionMac)
        _ <- env.sessionRepository.find.succeedsWith(Some(session))
        _ <- env.secureRandom.nextUUIDv7.succeedsWith(uuid)
        _ <- env.conversationRepository.create.succeedsWith(())
        _ <- env.userRepository.find.succeedsWith(Some(hintUser))
        _ <- env.conversationRouter.submit.succeedsWith(ConversationResult.RenderStep(ConversationStep.Credential(Nil, false, false)))
        result <- env.service.authorize(baseRequest.copy(sessionId = Some(rawSessionId), idTokenHint = Some(hint)))
        createCalls = env.conversationRepository.create.calls
      yield assertTrue(
        result == AuthorizeResponse.Initialize(AuthId(uuid)),
        // must NOT seed amr from the mismatched session
        createCalls.head._2.amr.isEmpty,
        createCalls.head._2.expectedUserId == Some(hintUserId),
      )
    },
  )

package versola.oauth.conversation

import versola.auth.model.OtpCode
import versola.auth.TestEnvConfig
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, PrimaryCredential}
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
import zio.http.URL
import zio.json.ast
import zio.test.*
import zio.ZIO

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
    step = ConversationStep.Empty(PrimaryCredential.Phone, passkey = false),
    requestedClaims = None,
    uiLocales = None,
    nonce = None,
    responseType = zio.prelude.NonEmptySet(versola.oauth.authorize.model.ResponseTypeEntry.Code),
    userEmail = None,
    userPhone = None,
    userLogin = None,
    userClaims = None,
  )

  val spec = suite("OtpConversationService")(
    suite("prepareInitialOtp")(
      test("create conversation record and send OTP") {
        val env = Env()
        for
          _ <- env.userRepository.findByCredential.succeedsWith(Some(UserRecord.empty(userId)))
          _ <- env.otpService.prepareOtp.succeedsWith(Some(realOtp))
          _ <- env.conversationRepository.overwrite.succeedsWith(())
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email))
        yield assertTrue(
          result == ConversationResult.RenderStep(realOtp),
        )
      },
      test("return LimitsExceeded when OTP generation fails") {
        val env = Env()
        for
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpService.prepareOtp.succeedsWith(None)
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email))
        yield assertTrue(
          result == ConversationResult.LimitsExceeded,
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
        )
        for
          _ <- env.userRepository.findByCredential.succeedsWith(Some(user))
          _ <- env.otpService.prepareOtp.succeedsWith(Some(realOtp))
          _ <- env.conversationRepository.overwrite.succeedsWith(())
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email))
          overwriteCalls = env.conversationRepository.overwrite.calls
        yield assertTrue(
          result == ConversationResult.RenderStep(realOtp),
          overwriteCalls.length == 2,
          overwriteCalls.head._2.userEmail.contains(userEmail),
          overwriteCalls.head._2.userPhone.contains(userPhone),
          overwriteCalls.head._2.userLogin.contains(userLogin),
          overwriteCalls.head._2.userClaims.contains(userClaims),
        )
      },
      test("not cache user information when user does not exist") {
        val env = Env()
        for
          _ <- env.userRepository.findByCredential.succeedsWith(None)
          _ <- env.otpService.prepareOtp.succeedsWith(Some(realOtp))
          _ <- env.conversationRepository.overwrite.succeedsWith(())
          _ <- env.otpService.sendOtp.succeedsWith(())
          result <- env.service.prepareInitialOtp(authId, initialConversation, Left(email))
          overwriteCalls = env.conversationRepository.overwrite.calls
        yield assertTrue(
          result == ConversationResult.RenderStep(realOtp),
          overwriteCalls.length == 2,
          overwriteCalls.head._2.userEmail.isEmpty,
          overwriteCalls.head._2.userPhone.isEmpty,
          overwriteCalls.head._2.userLogin.isEmpty,
          overwriteCalls.head._2.userClaims.isEmpty,
        )
      },
    ),
    suite("checkOtp")(
      test("return Success when OTP is correct") {
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
        )
        for
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.Success)
          result <- env.service.checkOtp(record, otp, otpCode, authId)
        yield assertTrue(result.isInstanceOf[ConversationResult.StepPassed])
      },
      test("return LimitsExceeded when too many attempts") {
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
        )
        for
          _ <- env.otpService.checkOtp.succeedsWith(SubmitOtpResult.LimitsExceeded)
          _ <- env.conversationRepository.delete.succeedsWith(())
          result <- env.service.checkOtp(record, otp, otpCode, authId)
        yield assertTrue(result == ConversationResult.LimitsExceeded)
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
          _ <- env.conversationRepository.delete.succeedsWith(())
          _ <- env.userInfoService.getUserInfoForIdToken.succeedsWith(
            UserInfoResponse(
              claims = Map("sub" -> ast.Json.Str(userId.toString), "email" -> ast.Json.Str(userEmail)),
            )
          )
          result <- env.service.finish(authId, conversation)
        yield assertTrue(
          result.idTokenData.isDefined,
          result.idTokenData.get.claims.contains("sub"),
          result.idTokenData.get.claims.contains("email"),
          result.idTokenData.get.clientId == clientId,
        )
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
          _ <- env.conversationRepository.delete.succeedsWith(())
          result <- env.service.finish(authId, conversation)
        yield assertTrue(result.idTokenData.isEmpty)
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
          _ <- env.conversationRepository.delete.succeedsWith(())
          result <- env.service.finish(authId, conversation)
        yield assertTrue(result.idTokenData.isEmpty)
      },
    ),
  )

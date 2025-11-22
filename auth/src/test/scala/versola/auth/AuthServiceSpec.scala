package versola.auth

import versola.auth.model.*
import versola.security.SecureRandom
import versola.user.UserRepository
import versola.user.model.*
import versola.util.{EnvName, ReloadingCache, UnitSpecBase}
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

object AuthServiceSpec extends UnitSpecBase:
  val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
  val futureTime = now.plusSeconds(900) // 15 minutes later
  val pastTime = now.minusSeconds(900)  // 15 minutes ago

  // Create AuthIds with current time so they're not expired
  val generatedAuthId = AuthId.fromInstant(now)
  val existingAuthId = AuthId.fromInstant(now)
  val expiredAuthId = AuthId.fromInstant(pastTime) // This will be expired
  val generatedOtpCode = OtpCode("1234")
  val wrongCode = OtpCode("9999")
  val email = Email("test@example.com")
  val bannedEmail = Email("banned@example.com")
  val anotherEmail = Email("another@example.com")

  val baseRecord = EmailVerificationRecord(email, existingAuthId, None, generatedOtpCode, 0)
  val expiredRecord = EmailVerificationRecord(email, expiredAuthId, None, generatedOtpCode, 2)

  val testUserId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val deviceId1 = DeviceId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
  val testUser = UserRecord.created(
    id = testUserId,
    email = email,
  )

  val testTokens = IssuedTokens(
    accessToken = AccessToken("test.access.token"),
    refreshToken = RefreshToken("test.refresh.token"),
    deviceId = None,
    user = Some(UserResponse.from(testUser)),
  )

  class Env(envName: EnvName = EnvName.Prod):
    //val smsService = stub[SmsClient]
    val userRepository = stub[UserRepository]
    val passkeyRepository = stub[PasskeyRepository]
    val tokenService = stub[TokenService]
    val emailVerificationsRepository = stub[EmailVerificationsRepository]
    val secureRandom = stub[SecureRandom]
    val bans = ReloadingCache.constant(Set(bannedEmail))

    val conversationService = stub[ConversationService]

    val authService = new AuthService.Impl(
      envName,
      userRepository,
      tokenService,
      emailVerificationsRepository,
      secureRandom,
      bans,
      passkeyRepository,
      conversationService
    )

  val spec = suite("AuthService")(
    suite("sendEmail") {
      case class Verify(
          emailSent: Boolean,
          updated: Boolean = false,
          overwritten: Boolean = false,
      )
      def testCase(
          description: String,
          foundRecord: Option[EmailVerificationRecord],
          expectedResult: AuthId,
          verify: Verify,
          email: Email = email,
          wonRaceRecord: Option[EmailVerificationRecord] = None,
          envName: EnvName = EnvName.Prod,
      ) = test(description) {
        val env = Env(envName)
        for
          _ <- TestClock.setTime(now)
          _ <- env.emailVerificationsRepository.find.succeedsWith(foundRecord)
          _ <- env.emailVerificationsRepository.create.succeedsWith(wonRaceRecord)
          _ <- env.emailVerificationsRepository.update.succeedsWith(())
          _ <- env.emailVerificationsRepository.overwrite.succeedsWith(())
         // _ <- env.emailService.sendVerificationEmail.succeedsWith(())
          _ <- env.secureRandom.nextNumeric.succeedsWith(generatedOtpCode)
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(generatedAuthId)
          _ <- env.conversationService.create.succeedsWith(ConversationRecord.create(generatedAuthId, ConversationStep.email))
          result <- env.authService.sendEmail(email, None)
          //sendEmailTimes = env.emailService.sendVerificationEmail.times
          updateTimes = env.emailVerificationsRepository.update.times
          overwriteTimes = env.emailVerificationsRepository.overwrite.times
        yield assertTrue(
          result == expectedResult,
          //sendEmailTimes == Option.when(verify.emailSent)(1).getOrElse(0),
          updateTimes == Option.when(verify.updated)(1).getOrElse(0),
          overwriteTimes == Option.when(verify.overwritten)(1).getOrElse(0),
          env.secureRandom.nextNumeric.calls.forall(_ == 6),
        )
      }
      List(
        testCase(
          description = "send email for new email address",
          foundRecord = None,
          expectedResult = generatedAuthId,
          verify = Verify(
            emailSent = true,
          ),
        ),
        testCase(
          description = "return existing token when limit exceeded and not expired",
          foundRecord = Some(baseRecord.copy(authId = existingAuthId, timesSent = 2)),
          expectedResult = existingAuthId,
          verify = Verify(
            emailSent = false,
          ),
        ),
        testCase(
          description = "resend email for existing record under limit",
          foundRecord = Some(baseRecord.copy(timesSent = 1)),
          expectedResult = existingAuthId,
          verify = Verify(
            emailSent = true,
            updated = true,
          ),
        ),
        testCase(
          description = "reset and send email when limit exceeded and expired",
          foundRecord = Some(expiredRecord),
          expectedResult = generatedAuthId,
          verify = Verify(
            emailSent = true,
            overwritten = true,
          ),
          email = email,
          wonRaceRecord = None,
          envName = EnvName.Prod,
        ),
        testCase(
          description = "do not send email for banned email but return token",
          foundRecord = None,
          expectedResult = generatedAuthId,
          email = bannedEmail,
          verify = Verify(
            emailSent = false,
          ),
        ),
        testCase(
          description = "do not send email in test environment",
          foundRecord = None,
          expectedResult = generatedAuthId,
          verify = Verify(
            emailSent = false,
          ),
          envName = EnvName.Test("test"),
        ),
        testCase(
          description = "handle race condition when create returns existing record",
          foundRecord = None,
          wonRaceRecord = Some(baseRecord.copy(authId = existingAuthId, code = OtpCode("5678"))),
          expectedResult = existingAuthId,
          verify = Verify(
            emailSent = true,
          ),
          email = email,
          envName = EnvName.Prod,
        ),
      )
    },
    suite("verifyEmail") {
      case class Verify(
          deleted: Boolean = false,
          userCreated: Boolean = false,
          tokensIssued: Boolean = false,
      )
      def testCase(
          description: String,
          foundRecord: Option[EmailVerificationRecord],
          expectedResult: Either[AttemptsLeft, IssuedTokens],
          verify: Verify,
          code: OtpCode = generatedOtpCode,
          token: AuthId = existingAuthId,
          envName: EnvName = EnvName.Prod,
      ) = test(description) {
        val env = Env(envName)
        for
          _ <- TestClock.setTime(now)
          _ <- env.emailVerificationsRepository.findByAuthId.succeedsWith(foundRecord)
          _ <- env.emailVerificationsRepository.delete.succeedsWith(())
          _ <- env.userRepository.findOrCreateByEmail.succeedsWith((testUser, WasCreated(verify.userCreated)))
          _ <- env.tokenService.issueTokens.succeedsWith(testTokens)
          _ <- env.conversationService.updateStep.succeedsWith(())
          result <- env.authService.verifyEmail(code, token).either
          deleteTimes = env.emailVerificationsRepository.delete.times
          findOrCreateTimes = env.userRepository.findOrCreateByEmail.times
          issueTokensTimes = env.tokenService.issueTokens.times
        yield assertTrue(
          result == expectedResult,
          deleteTimes == Option.when(verify.deleted)(1).getOrElse(0),
          findOrCreateTimes == Option.when(verify.userCreated || verify.tokensIssued)(1).getOrElse(0),
          issueTokensTimes == Option.when(verify.tokensIssued)(1).getOrElse(0),
        )
      }
      List(
        testCase(
          description = "return None when token not found",
          foundRecord = None,
          expectedResult = Left(AttemptsLeft(0)),
          verify = Verify(),
        ),
        testCase(
          description = "return None when email is banned",
          foundRecord = Some(baseRecord.copy(email = bannedEmail)),
          expectedResult = Left(AttemptsLeft(1)),
          verify = Verify(),
          code = generatedOtpCode,
          token = existingAuthId,
          envName = EnvName.Prod,
        ),
        testCase(
          description = "delete record and return None when token expired",
          foundRecord = Some(expiredRecord),
          expectedResult = Left(AttemptsLeft(0)),
          verify = Verify(deleted = true),
          code = generatedOtpCode,
          token = expiredAuthId,
          envName = EnvName.Prod,
        ),
        testCase(
          description = "delete record and return tokens when code matches",
          foundRecord = Some(baseRecord),
          expectedResult = Right(testTokens),
          verify = Verify(deleted = true, tokensIssued = true),
        ),
        testCase(
          description = "return None when code does not match",
          foundRecord = Some(baseRecord),
          code = wrongCode,
          expectedResult = Left(AttemptsLeft(1)),
          verify = Verify(),
        ),
        testCase(
          description = "create new user and return tokens when user doesn't exist",
          foundRecord = Some(baseRecord),
          expectedResult = Right(testTokens),
          verify = Verify(deleted = true, userCreated = true, tokensIssued = true),
        ),
        testCase(
          description = "return tokens when code not matches in test environment",
          foundRecord = Some(baseRecord.copy(code = wrongCode)),
          envName = EnvName.Test("test"),
          expectedResult = Right(testTokens),
          verify = Verify(
            deleted = true,
            tokensIssued = true,
          ),
          code = generatedOtpCode,
          token = existingAuthId,
        ),
      )
    },
    suite("logout")(
      test("successfully logout user with valid userId and deviceId") {
        val env = Env()
        for
          _ <- env.tokenService.logout.succeedsWith(())
          result <- env.authService.logout(testUser.id, deviceId1)
          logoutCalls = env.tokenService.logout.calls
        yield assertTrue(
          logoutCalls.length == 1,
          logoutCalls.head == (testUser.id, deviceId1),
        )
      },
      test("handle token service failure during logout") {
        val env = Env()
        for
          _ <- env.tokenService.logout.failsWith(RuntimeException("Database connection failed"))
          result <- env.authService.logout(testUser.id, deviceId1).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.get.getMessage == "Database connection failed",
        )
      },
    ),
  )

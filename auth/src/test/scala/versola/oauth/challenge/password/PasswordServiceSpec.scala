package versola.oauth.challenge.password

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.auth.model.{Password, PasswordRecord}
import versola.oauth.challenge.password.model.*
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PasswordHistorySettings, TenantId}
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.conversation.otp.{EmailOtpProvider, SmsOtpProvider}
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import versola.util.*
import zio.*
import zio.prelude.EqualOps
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object PasswordServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val clientId = ClientId("test-client")
  private val tenantId = TenantId("test-tenant")
  private val userId = UserId(UUID.randomUUID())
  private val email = Email("user@example.com")
  private val phone = Phone("+12025551234")
  private val password = Password("Test1234!")
  private val testSalt = Salt("salt".getBytes)
  private val testHash = MAC("hash".getBytes)
  private val differentHash = MAC("diff".getBytes)
  private val coreConfig = TestEnvConfig.coreConfig

  private val userRecord =
    UserRecord.empty(userId).copy(email = Some(email), phone = Some(phone))

  private def permRecord(hash: MAC, createdAt: Instant = Instant.EPOCH): PasswordRecord =
    PasswordRecord(
      id = 0L,
      userId = userId,
      password = Secret(hash),
      salt = testSalt,
      createdAt = createdAt,
      expiresAt = None,
    )

  private def tempRecord(hash: MAC, expiresAt: Instant): PasswordRecord =
    PasswordRecord(
      id = 0L,
      userId = userId,
      password = Secret(hash),
      salt = testSalt,
      createdAt = Instant.EPOCH,
      expiresAt = Some(expiresAt),
    )

  class Env(secureRandom: SecureRandom, env: EnvName = EnvName.Test("dev")):
    val passwordRepo = stub[PasswordRepository]
    val configuration = stub[OAuthConfigurationService]
    val securityService = stub[SecurityService]
    val emailProvider = stub[EmailOtpProvider]
    val smsProvider = stub[SmsOtpProvider]
    val userRepo = stub[UserRepository]
    val service = PasswordService.Impl(
      passwordRepo,
      securityService,
      secureRandom,
      coreConfig,
      configuration,
      env,
      userRepo,
      emailProvider,
      smsProvider,
    )

  def spec = suite("PasswordService")(
    suite("verifyPassword")(
      test("returns TemporaryExpired when temp password is expired") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.passwordRepo.list.succeedsWith(Vector(tempRecord(testHash, Instant.EPOCH.minusSeconds(1))))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          result <- env.service.verifyPassword(userId, password)
        yield assertTrue(result == CheckPassword.TemporaryExpired)
      },
      test("returns Temporary when temp password matches and is not expired") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.passwordRepo.list.succeedsWith(Vector(tempRecord(testHash, Instant.MAX)))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          result <- env.service.verifyPassword(userId, password)
        yield assertTrue(result == CheckPassword.Temporary)
      },
      test("returns Failure when temp password does not match") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.passwordRepo.list.succeedsWith(Vector(tempRecord(differentHash, Instant.MAX)))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          result <- env.service.verifyPassword(userId, password)
        yield assertTrue(result == CheckPassword.Failure)
      },
      test("returns Success when permanent password matches") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.passwordRepo.list.succeedsWith(Vector(permRecord(testHash)))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          result <- env.service.verifyPassword(userId, password)
        yield assertTrue(result == CheckPassword.Success)
      },
      test("returns OldPassword when an old password matches") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          current = permRecord(differentHash)
          old = permRecord(testHash, Instant.EPOCH.minusSeconds(100))
          _ <- env.passwordRepo.list.succeedsWith(Vector(current, old))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          result <- env.service.verifyPassword(userId, password)
        yield assertTrue(result == CheckPassword.OldPassword(old.createdAt))
      },
      test("returns Failure when no password matches") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.passwordRepo.list.succeedsWith(Vector(permRecord(differentHash)))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          result <- env.service.verifyPassword(userId, password)
        yield assertTrue(result == CheckPassword.Failure)
      },
      test("returns Failure when the user has no password") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.passwordRepo.list.succeedsWith(Vector.empty)
          result <- env.service.verifyPassword(userId, password)
        yield assertTrue(result == CheckPassword.Failure)
      },
    ),
    suite("setPassword")(
      test("hashes password and saves with history settings") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.configuration.getPasswordHistorySettings.succeedsWith(PasswordHistorySettings(historySize = 5, numDifferent = 3))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.create.succeedsWith(())
          _ <- env.service.setPassword(clientId, userId, password)
          createCalls = env.passwordRepo.create.calls
        yield assertTrue(
          createCalls.length == 1,
          createCalls.head._1 == userId,
          MAC(createCalls.head._2) === testHash,
          createCalls.head._4 == 5,
          createCalls.head._5 == 3,
        )
      },
      test("fails with PasswordReuseError when repository rejects reuse") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.configuration.getPasswordHistorySettings.succeedsWith(PasswordHistorySettings(historySize = 5, numDifferent = 3))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.create.failsWith(PasswordReuseError(3))
          result <- env.service.setPassword(clientId, userId, password).exit
        yield assert(result)(fails(equalTo(PasswordReuseError(3))))
      },
    ),
    suite("resetPassword")(
      test("generates and stores a temporary password with the default TTL") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          now <- Clock.instant
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          _ <- env.service.resetPassword(userId, None, None)
          createCalls = env.passwordRepo.createTemporary.calls
        yield assertTrue(
          createCalls.length == 1,
          createCalls.head._1 == userId,
          !createCalls.head._4.isBefore(now.plusSeconds(11 * 60 * 60)),
          env.emailProvider.send.calls.isEmpty,
          env.smsProvider.send.calls.isEmpty,
        )
      },
      test("delivers via email in prod when the channel is email") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getPasswordTemplate.succeedsWith(OtpTemplate("Pass: {{password}}, hours: {{expiresHours}}"))
          _ <- env.userRepo.find.succeedsWith(Some(userRecord))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          _ <- env.emailProvider.send.succeedsWith(())
          _ <- env.service.resetPassword(userId, Some(3600L), Some(DeliveryChannel.email))
          sendCalls = env.emailProvider.send.calls
        yield assertTrue(
          sendCalls.length == 1,
          sendCalls.head._1 == email,
          sendCalls.head._2.contains("hours: 1"),
          env.smsProvider.send.calls.isEmpty,
        )
      },
      test("delivers via SMS in prod when the channel is sms") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getPasswordTemplate.succeedsWith(OtpTemplate("Pass: {{password}}"))
          _ <- env.userRepo.find.succeedsWith(Some(userRecord))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          _ <- env.smsProvider.send.succeedsWith(())
          _ <- env.service.resetPassword(userId, None, Some(DeliveryChannel.sms))
          sendCalls = env.smsProvider.send.calls
        yield assertTrue(
          sendCalls.length == 1,
          sendCalls.head._1 == phone,
          env.emailProvider.send.calls.isEmpty,
        )
      },
      test("does not deliver when the channel is None") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          _ <- env.service.resetPassword(userId, None, None)
        yield assertTrue(
          env.emailProvider.send.calls.isEmpty,
          env.smsProvider.send.calls.isEmpty,
        )
      },
      test("does not deliver in a non-prod environment") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          _ <- env.service.resetPassword(userId, Some(3600L), Some(DeliveryChannel.email))
        yield assertTrue(
          env.passwordRepo.createTemporary.calls.length == 1,
          env.emailProvider.send.calls.isEmpty,
          env.smsProvider.send.calls.isEmpty,
        )
      },
      test("generates a password satisfying the tenant regex") {
        val regex = "^[A-Z].*"
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(regex)
          _ <- env.configuration.getPasswordTemplate.succeedsWith(OtpTemplate("{{password}}"))
          _ <- env.userRepo.find.succeedsWith(Some(userRecord))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          _ <- env.emailProvider.send.succeedsWith(())
          _ <- env.service.resetPassword(userId, None, Some(DeliveryChannel.email))
          delivered = env.emailProvider.send.calls.head._2
        yield assertTrue(delivered.matches(regex))
      },
      test("fails without storing a credential when no password can satisfy the regex") {
        val impossibleRegex = "^.{100,}$"
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(impossibleRegex)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          result <- env.service.resetPassword(userId, None, None).exit
        yield assert(result)(fails(isSubtype[TemporaryPasswordGenerationFailed](anything))) &&
          assertTrue(env.passwordRepo.createTemporary.calls.isEmpty)
      },
      test("fails without storing a credential when email delivery is requested but the user has no email") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.userRepo.find.succeedsWith(Some(UserRecord.empty(userId).copy(phone = Some(phone))))
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          result <- env.service.resetPassword(userId, None, Some(DeliveryChannel.email)).exit
        yield assert(result)(fails(equalTo(PasswordDeliveryUnavailable(userId, DeliveryChannel.email)))) &&
          assertTrue(
            env.passwordRepo.createTemporary.calls.isEmpty,
            env.emailProvider.send.calls.isEmpty,
          )
      },
      test("fails without storing a credential when SMS delivery is requested but the user has no phone") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.userRepo.find.succeedsWith(Some(UserRecord.empty(userId).copy(email = Some(email))))
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          result <- env.service.resetPassword(userId, None, Some(DeliveryChannel.sms)).exit
        yield assert(result)(fails(equalTo(PasswordDeliveryUnavailable(userId, DeliveryChannel.sms)))) &&
          assertTrue(
            env.passwordRepo.createTemporary.calls.isEmpty,
            env.smsProvider.send.calls.isEmpty,
          )
      },
      test("fails without storing a credential when the user does not exist") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.userRepo.find.succeedsWith(None)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          result <- env.service.resetPassword(userId, None, Some(DeliveryChannel.email)).exit
        yield assert(result)(fails(equalTo(PasswordDeliveryUnavailable(userId, DeliveryChannel.email)))) &&
          assertTrue(env.passwordRepo.createTemporary.calls.isEmpty)
      },
      test("removes the stored temporary password when delivery fails") {
        for
          secureRandom <- ZIO.service[SecureRandom]
          env = Env(secureRandom, EnvName.Prod)
          _ <- env.configuration.getPasswordRegex.succeedsWith(".*")
          _ <- env.configuration.getPasswordTemplate.succeedsWith(OtpTemplate("Pass: {{password}}"))
          _ <- env.userRepo.find.succeedsWith(Some(userRecord))
          _ <- env.securityService.hashPassword.succeedsWith(testHash)
          _ <- env.passwordRepo.createTemporary.succeedsWith(())
          _ <- env.passwordRepo.deleteTemporary.succeedsWith(())
          _ <- env.emailProvider.send.failsWith(new RuntimeException("smtp down"))
          result <- env.service.resetPassword(userId, None, Some(DeliveryChannel.email)).exit
        yield assert(result)(fails(anything)) &&
          assertTrue(
            env.passwordRepo.createTemporary.calls.length == 1,
            env.passwordRepo.deleteTemporary.calls == List(userId),
          )
      },
    ),
  ).provideShared(SecureRandom.live) @@ TestAspect.silentLogging

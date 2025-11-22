package versola.auth

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import org.apache.commons.codec.digest.Blake3
import versola.auth.model.{AuthId, DeviceId, RefreshToken, UserDeviceRecord}
import versola.security.{MAC, Secret, SecureRandom, SecurityService}
import versola.user.model.{Email, UserId, UserRecord}
import versola.util.UnitSpecBase
import zio.*
import zio.test.*

import java.time.Instant
import java.util.{Base64, UUID}

object TokenServiceSpec extends UnitSpecBase:

  // Test data
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val email1 = Email("test@example.com")
  val authId1 = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val deviceId1 = DeviceId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
  val now = Instant.now()

  val testUser = UserRecord.created(
    id = userId1,
    email = email1,
  )

  class Env:
    val secureRandom = stub[SecureRandom]
    val userDeviceRepository = stub[UserDeviceRepository]
    val securityService = stub[SecurityService]
    val tokenService = TokenService.Impl(
      userDeviceRepository,
      secureRandom,
      securityService,
      TestEnvConfig.jwtConfig,
      TestEnvConfig.coreConfig.security.refreshTokens,
    )

  val spec = suite("TokenService")(
    suite("issueTokens")(
      test("generate valid JWT tokens with correct structure and claims") {
        val env = Env()
        for
          _ <- env.secureRandom.nextHex.succeedsWith("abcdef1234567890")
          _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(64)(1.toByte))
          _ <- env.secureRandom.nextUUIDv7.succeedsWith(deviceId1)
          _ <- env.userDeviceRepository.overwrite.succeedsWith(())
          result <- env.tokenService.issueTokens(testUser, authId1, None)
          secureRandomCalls = env.secureRandom.nextHex.calls.length
          byteCalls = env.secureRandom.nextBytes.calls.length
          uuidCalls = env.secureRandom.nextUUIDv7.times
          overwriteCalls = env.userDeviceRepository.overwrite.calls

          // Verify JWT structure and claims
          accessTokenValid <- ZIO.attempt {
            val jwt = SignedJWT.parse(result.accessToken)
            val verifier = RSASSAVerifier(TestEnvConfig.publicKey)
            jwt.verify(verifier) &&
            jwt.getJWTClaimsSet.getSubject == testUser.id.toString &&
            jwt.getJWTClaimsSet.getIssuer == "app.dvor" &&
            jwt.getJWTClaimsSet.getAudience.contains("app.dvor") &&
            jwt.getJWTClaimsSet.getClaim("auth_id") == authId1.toString &&
            jwt.getJWTClaimsSet.getClaim("device_id") == deviceId1.toString &&
            jwt.getHeader.getKeyID == "test-key-id" &&
            jwt.getHeader.getType.getType == "at+jwt"
          }.catchAll(_ => ZIO.succeed(false))
        yield assertTrue(
          secureRandomCalls == 1, // One for access token
          byteCalls == 1,         // One for refresh token
          uuidCalls == 1,         // One for device ID generation
          overwriteCalls.length == 1,
          accessTokenValid,
          result.deviceId.isDefined, // Should generate device ID when not provided
        )
      },
      test("use provided device ID when given") {
        val env = Env()
        for
          _ <- env.secureRandom.nextHex.succeedsWith("abcdef1234567890")
          _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(64)(1.toByte))
          _ <- env.userDeviceRepository.overwrite.succeedsWith(())
          result <- env.tokenService.issueTokens(testUser, authId1, Some(deviceId1))
          overwriteCalls = env.userDeviceRepository.overwrite.calls
        yield assertTrue(
          overwriteCalls.length == 1,
          overwriteCalls.head.deviceId == deviceId1,
          result.deviceId.isEmpty, // Should not return device ID when provided
        )
      },
    ),
    suite("reissueTokens")(
      test("successfully reissue tokens for valid refresh token") {
        val env = Env()
        val refreshToken = RefreshToken("test-refresh-token")

        // Compute MAC the same way TokenService does
        val refreshTokenBytes = Base64.getUrlDecoder.decode(refreshToken)
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(TestEnvConfig.coreConfig.security.refreshTokens.pepper)
          .update(refreshTokenBytes)
          .doFinalize(mac)
        val expectedMac = MAC(mac)

        val blake3Hash = RefreshToken.fromBytes(expectedMac)
        val existingRecord = UserDeviceRecord(userId1, deviceId1, authId1, Some(blake3Hash), Some(now.plusSeconds(90 * 24 * 3600)))

        for
          _ <- TestClock.setTime(now) // Set fixed time to avoid timing issues
          _ <- env.secureRandom.nextHex.succeedsWith("newtoken123456")
          _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(64)(2.toByte))
          _ <- env.securityService.macBlake3.succeedsWith(expectedMac)
          _ <- env.userDeviceRepository.findByRefreshToken.succeedsWith(Some(existingRecord))
          _ <- env.userDeviceRepository.update.succeedsWith(())
          _ <- env.userDeviceRepository.clearRefreshByUserIdAndDeviceId.succeedsWith(()) // Should not be called but stub it anyway
          result <- env.tokenService.reissueTokens(refreshToken, deviceId1)
          findCalls = env.userDeviceRepository.findByRefreshToken.calls
          updateCalls = env.userDeviceRepository.update.calls
          clearCalls = env.userDeviceRepository.clearRefreshByUserIdAndDeviceId.calls

          // Verify JWT structure and claims
          accessTokenValid <- ZIO.attempt {
            val jwt = SignedJWT.parse(result.accessToken)
            val verifier = RSASSAVerifier(TestEnvConfig.publicKey)
            jwt.verify(verifier) &&
            jwt.getJWTClaimsSet.getSubject == userId1.toString &&
            jwt.getJWTClaimsSet.getIssuer == "app.dvor" &&
            jwt.getJWTClaimsSet.getAudience.contains("app.dvor") &&
            jwt.getJWTClaimsSet.getClaim("auth_id") == authId1.toString &&
            jwt.getJWTClaimsSet.getClaim("device_id") == deviceId1.toString
          }.catchAll(_ => ZIO.succeed(false))
        yield assertTrue(
          findCalls.length == 1,
          findCalls.head.equals(expectedMac),
          updateCalls.length == 1,
          clearCalls.length == 0, // Should not be called in successful case
          accessTokenValid,
          result.deviceId.isEmpty, // Device ID not returned on reissue
        )
      },
      test("fail when refresh token not found") {
        val env = Env()
        val refreshToken = RefreshToken("nonexistent-token")

        val refreshTokenBytes = Base64.getUrlDecoder.decode(refreshToken)
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(TestEnvConfig.coreConfig.security.refreshTokens.pepper)
          .update(refreshTokenBytes)
          .doFinalize(mac)
        val expectedMac = MAC(mac)

        for
          _ <- env.securityService.macBlake3.succeedsWith(expectedMac)
          _ <- env.userDeviceRepository.findByRefreshToken.succeedsWith(None)
          result <- env.tokenService.reissueTokens(refreshToken, deviceId1).exit
        yield assertTrue(result.isFailure)
      },
      test("fail when refresh token expired") {
        val env = Env()
        val refreshToken = RefreshToken("expired-token")

        val refreshTokenBytes = Base64.getUrlDecoder.decode(refreshToken)
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(TestEnvConfig.coreConfig.security.refreshTokens.pepper)
          .update(refreshTokenBytes)
          .doFinalize(mac)
        val expectedMac = MAC(mac)

        val blake3Hash = RefreshToken.fromBytes(expectedMac)
        val expiredRecord = UserDeviceRecord(userId1, deviceId1, authId1, Some(blake3Hash), Some(now.minusSeconds(3600)))

        for
          _ <- env.securityService.macBlake3.succeedsWith(expectedMac)
          _ <- env.userDeviceRepository.findByRefreshToken.succeedsWith(Some(expiredRecord))
          _ <- env.userDeviceRepository.clearRefreshByUserIdAndDeviceId.succeedsWith(())
          result <- env.tokenService.reissueTokens(refreshToken, deviceId1).exit
        yield assertTrue(result.isFailure)
      },
      test("fail when device ID doesn't match") {
        val env = Env()
        val refreshToken = RefreshToken("valid-token")

        val refreshTokenBytes = Base64.getUrlDecoder.decode(refreshToken)
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(TestEnvConfig.coreConfig.security.refreshTokens.pepper)
          .update(refreshTokenBytes)
          .doFinalize(mac)
        val expectedMac = MAC(mac)

        val blake3Hash = RefreshToken.fromBytes(expectedMac)
        val wrongDeviceRecord = UserDeviceRecord(userId1, deviceId1, authId1, Some(blake3Hash), Some(now.plusSeconds(90 * 24 * 3600)))
        val differentDeviceId = DeviceId(UUID.fromString("99999999-8888-7777-6666-555555555555"))

        for
          _ <- env.securityService.macBlake3.succeedsWith(expectedMac)
          _ <- env.userDeviceRepository.findByRefreshToken.succeedsWith(Some(wrongDeviceRecord))
          _ <- env.userDeviceRepository.clearRefreshByUserIdAndDeviceId.succeedsWith(())
          result <- env.tokenService.reissueTokens(refreshToken, differentDeviceId).exit
        yield assertTrue(result.isFailure)
      },
    ),
    suite("logout")(
      test("successfully clear refresh token for user and device") {
        val env = Env()
        for
          _ <- env.userDeviceRepository.clearRefreshByUserIdAndDeviceId.succeedsWith(())
          result <- env.tokenService.logout(userId1, deviceId1)
          clearCalls = env.userDeviceRepository.clearRefreshByUserIdAndDeviceId.calls
        yield assertTrue(
          clearCalls.length == 1,
          clearCalls.head == (userId1, deviceId1),
        )
      },
      test("handle repository failure during logout") {
        val env = Env()
        for
          _ <- env.userDeviceRepository.clearRefreshByUserIdAndDeviceId.failsWith(RuntimeException("Database connection failed"))
          result <- env.tokenService.logout(userId1, deviceId1).either
        yield assertTrue(
          result.isLeft,
          result.left.toOption.get.getMessage == "Database connection failed",
        )
      },
    ),
  )

package versola.auth

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AuthId, DeviceId, UserDeviceRecord}
import versola.user.model.UserId
import versola.util.DatabaseSpecBase
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

trait UserDeviceRepositorySpec extends DatabaseSpecBase[UserDeviceRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
  val deviceId1 = DeviceId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
  val deviceId2 = DeviceId(UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa"))
  val authId1 = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val authId2 = AuthId(UUID.fromString("ffffffff-0000-1111-2222-333333333333"))
  val authId3 = AuthId(UUID.fromString("12345678-9abc-def0-1234-567890abcdef"))
  val blake3Hash1 = "hash1"
  val blake3Hash2 = "hash2"
  val blake3Hash3 = "hash3"
  val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
  val expireAt = now.plusSeconds(90 * 24 * 3600) // 90 days

  val record1 = UserDeviceRecord(userId1, deviceId1, authId1, Some(blake3Hash1), Some(expireAt))
  val record2 = UserDeviceRecord(userId1, deviceId2, authId2, Some(blake3Hash2), Some(expireAt))
  val record3 = UserDeviceRecord(userId2, deviceId1, authId3, Some(blake3Hash3), Some(expireAt))

  override def testCases(env: UserDeviceRepositorySpec.Env) =
    List(
      overwriteTests(env),
      findByRefreshTokenHashTests(env),
      updateTests(env),
      listByUserIdTests(env),
      clearRefreshByUserIdAndDeviceIdTests(env),
    )

  def overwriteTests(env: UserDeviceRepositorySpec.Env) =
    suite("overwrite")(
      test("create new record when it doesn't exist") {
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          found <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
        yield assertTrue(found.contains(record1))
      },
      test("update existing record when user-device combination exists") {
        val updatedRecord = record1.copy(authId = authId2, refreshTokenBlake3 = Some(blake3Hash2))
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          _ <- env.userDeviceRepository.overwrite(updatedRecord)
          found <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash2)
          notFound <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
        yield assertTrue(
          found.contains(updatedRecord),
          notFound.isEmpty
        )
      },
      test("allow multiple devices for same user") {
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          _ <- env.userDeviceRepository.overwrite(record2)
          found1 <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
          found2 <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash2)
        yield assertTrue(
          found1.contains(record1),
          found2.contains(record2)
        )
      },
      test("allow same device for different users") {
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          _ <- env.userDeviceRepository.overwrite(record3)
          found1 <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
          found3 <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash3)
        yield assertTrue(
          found1.contains(record1),
          found3.contains(record3)
        )
      },
    )

  def findByRefreshTokenHashTests(env: UserDeviceRepositorySpec.Env) =
    suite("findByRefreshTokenHash")(
      test("return Some when record exists") {
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          found <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
        yield assertTrue(found.contains(record1))
      },
      test("return None when record does not exist") {
        for
          found <- env.userDeviceRepository.findByRefreshTokenHash("nonexistent")
        yield assertTrue(found.isEmpty)
      },
    )

  def updateTests(env: UserDeviceRepositorySpec.Env) =
    suite("update")(
      test("update existing record") {
        val newExpireAt = expireAt.plusSeconds(3600)
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          _ <- env.userDeviceRepository.update(blake3Hash1, blake3Hash2, newExpireAt)
          notFound <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
          found <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash2)
        yield assertTrue(
          notFound.isEmpty,
          found.isDefined,
          found.get.refreshTokenBlake3 == Some(blake3Hash2),
          found.get.expireAt == Some(newExpireAt)
        )
      },
      test("do nothing when old hash doesn't exist") {
        for
          _ <- env.userDeviceRepository.update("nonexistent", blake3Hash2, expireAt)
          found <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash2)
        yield assertTrue(found.isEmpty)
      },
    )

  def listByUserIdTests(env: UserDeviceRepositorySpec.Env) =
    suite("listByUserId")(
      test("return empty vector when user has no devices") {
        for
          devices <- env.userDeviceRepository.listByUserId(userId1)
        yield assertTrue(devices.isEmpty)
      },
      test("return all devices for user") {
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          _ <- env.userDeviceRepository.overwrite(record2)
          _ <- env.userDeviceRepository.overwrite(record3) // Different user
          devices <- env.userDeviceRepository.listByUserId(userId1)
        yield assertTrue(
          devices.length == 2,
          devices.contains(record1),
          devices.contains(record2),
          !devices.contains(record3)
        )
      },
    )

  def clearRefreshByUserIdAndDeviceIdTests(env: UserDeviceRepositorySpec.Env) =
    suite("clearRefreshByUserIdAndDeviceId")(
      test("clear refresh token for specific user and device") {
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          _ <- env.userDeviceRepository.overwrite(record2)
          _ <- env.userDeviceRepository.clearRefreshByUserIdAndDeviceId(userId1, deviceId1)
          clearedRecord <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
          untouchedRecord <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash2)
        yield assertTrue(
          clearedRecord.isEmpty, // Should not be found by refresh token hash anymore
          untouchedRecord.contains(record2), // Other device should remain untouched
        )
      },
      test("do nothing when user-device combination doesn't exist") {
        for
          _ <- env.userDeviceRepository.overwrite(record1)
          _ <- env.userDeviceRepository.clearRefreshByUserIdAndDeviceId(userId2, deviceId2) // Different user/device
          untouchedRecord <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
        yield assertTrue(
          untouchedRecord.contains(record1), // Original record should remain untouched
        )
      },
      test("clear only the specified device for a user with multiple devices") {
        for
          _ <- env.userDeviceRepository.overwrite(record1) // userId1, deviceId1
          _ <- env.userDeviceRepository.overwrite(record2) // userId1, deviceId2
          _ <- env.userDeviceRepository.clearRefreshByUserIdAndDeviceId(userId1, deviceId1)
          clearedRecord <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash1)
          remainingRecord <- env.userDeviceRepository.findByRefreshTokenHash(blake3Hash2)
        yield assertTrue(
          clearedRecord.isEmpty, // First device should be cleared
          remainingRecord.contains(record2), // Second device should remain
        )
      },
    )

object UserDeviceRepositorySpec:
  case class Env(userDeviceRepository: UserDeviceRepository)

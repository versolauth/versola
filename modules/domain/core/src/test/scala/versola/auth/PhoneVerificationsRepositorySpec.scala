package versola.auth

object PhoneVerificationsRepositorySpec
/*import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AuthId, OtpCode, PhoneVerificationRecord}
import versola.util.{DatabaseSpecBase, Phone}
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

trait PhoneVerificationsRepositorySpec extends DatabaseSpecBase[PhoneVerificationsRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val phone1 = Phone("+79152234455")
  val phone2 = Phone("+12025550123")
  val authId1 = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val authId2 = AuthId(UUID.fromString("ffffffff-0000-1111-2222-333333333333"))
  val code1 = OtpCode("code1")
  val code2 = OtpCode("code2")
  val now = Instant.now()
  val expireAt = now.plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS)

  val record1 = PhoneVerificationRecord(phone1, authId1, None, code1, 0)
  val record2 = PhoneVerificationRecord(phone2, authId2, None, code2, 0)

  override def testCases(env: PhoneVerificationsRepositorySpec.Env) =
    List(
      create(env),
      find(env),
      findByAuthId(env),
      update(env),
      overwrite(env),
      delete(env),
    )

  def create(env: PhoneVerificationsRepositorySpec.Env) =
    suite("create")(
      test("successfully create a new record") {
        for
          previous <- env.phoneVerificationsRepository.create(record1)
          created <- env.phoneVerificationsRepository.find(record1.phone)
        yield assertTrue(
          previous.isEmpty,
          created.contains(record1),
        )
      },
      test("return previous record when phone already exists") {
        for
          _ <- env.phoneVerificationsRepository.create(record1)
          previous <- env.phoneVerificationsRepository.create(record1.copy(authId = authId2))
        yield assertTrue(
          previous.contains(record1),
        )
      },
    )

  def find(env: PhoneVerificationsRepositorySpec.Env) =
    suite("find")(
      test("return Some when record exists") {
        for
          _ <- env.phoneVerificationsRepository.create(record1)
          record <- env.phoneVerificationsRepository.find(record1.phone)
        yield assertTrue(record.contains(record1))
      },
      test("return None when record does not exist") {
        for
          record <- env.phoneVerificationsRepository.find(phone2)
        yield assertTrue(record.isEmpty)
      },
    )

  def findByAuthId(env: PhoneVerificationsRepositorySpec.Env) =
    suite("findByAuthId")(
      test("return Some when record exists") {
        for
          _ <- env.phoneVerificationsRepository.create(record1)
          record <- env.phoneVerificationsRepository.findByAuthId(record1.authId)
        yield assertTrue(record.contains(record1))
      },
      test("return None when record does not exist") {
        for
          record <- env.phoneVerificationsRepository.findByAuthId(authId2)
        yield assertTrue(record.isEmpty)
      },
      test("return correct record when multiple records exist") {
        for
          _ <- env.phoneVerificationsRepository.create(record1)
          _ <- env.phoneVerificationsRepository.create(record2)
          record1Found <- env.phoneVerificationsRepository.findByAuthId(record1.authId)
          record2Found <- env.phoneVerificationsRepository.findByAuthId(record2.authId)
        yield assertTrue(
          record1Found.contains(record1),
          record2Found.contains(record2),
        )
      },
    )

  def update(env: PhoneVerificationsRepositorySpec.Env) =
    suite("update")(
      test("successfully update record") {
        for
          _ <- env.phoneVerificationsRepository.create(record1)
          _ <- env.phoneVerificationsRepository.update(record1.phone, code2, timesSent = 1)
          record <- env.phoneVerificationsRepository.find(record1.phone)
        yield assertTrue(
          record.contains(record1.copy(code = code2, timesSent = 1)),
        )
      },
      test("do nothing when record does not exist") {
        for
          _ <- env.phoneVerificationsRepository.update(phone2, code2, timesSent = 1)
          record <- env.phoneVerificationsRepository.find(phone2)
        yield assertTrue(record.isEmpty)
      },
    )

  def overwrite(env: PhoneVerificationsRepositorySpec.Env) =
    suite("overwrite")(
      test("successfully overwrite record") {
        for
          _ <- env.phoneVerificationsRepository.create(record1)
          _ <- env.phoneVerificationsRepository.overwrite(record1.copy(authId = authId2))
          record <- env.phoneVerificationsRepository.find(record1.phone)
        yield assertTrue(
          record.contains(record1.copy(authId = authId2)),
        )
      },
      test("create new record when it does not exist") {
        for
          _ <- env.phoneVerificationsRepository.overwrite(record2)
          record <- env.phoneVerificationsRepository.find(record2.phone)
        yield assertTrue(
          record.contains(record2),
        )
      },
    )

  def delete(env: PhoneVerificationsRepositorySpec.Env) =
    suite("delete")(
      test("successfully delete record") {
        for
          _ <- env.phoneVerificationsRepository.create(record1)
          _ <- env.phoneVerificationsRepository.delete(record1.phone)
          record <- env.phoneVerificationsRepository.find(record1.phone)
        yield assertTrue(record.isEmpty)
      },
      test("do nothing when record does not exist") {
        for
          _ <- env.phoneVerificationsRepository.delete(phone2)
          record <- env.phoneVerificationsRepository.find(phone2)
        yield assertTrue(record.isEmpty)
      },
    )

object PhoneVerificationsRepositorySpec:
  case class Env(
      phoneVerificationsRepository: PhoneVerificationsRepository,
  )*/

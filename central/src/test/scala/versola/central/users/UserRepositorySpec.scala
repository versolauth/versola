package versola.central.users

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.{DatabaseSpecBase, Email, Phone, SecureRandom}
import zio.test.*
import zio.{ZIO, ZLayer}

import java.util.UUID

trait UserRepositorySpec extends DatabaseSpecBase[UserRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val userId1 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val userId2 = UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
  private val email   = Email("user@example.com")
  private val phone   = Phone("+15551234567")
  private val login   = Login("nickname")

  override def testCases(env: UserRepositorySpec.Env) =
    List(
      test("create inserts user and is retrievable by email/phone/login") {
        for
          _ <- env.repository.create(userId1, Some(email), Some(phone), Some(login))
          byEmail <- env.repository.findByEmail(email)
          byPhone <- env.repository.findByPhone(phone)
          byLogin <- env.repository.findByLogin(login)
        yield assertTrue(
          byEmail.contains(UserIndexRecord(userId1, Some(email), Some(phone), Some(login))),
          byPhone.contains(UserIndexRecord(userId1, Some(email), Some(phone), Some(login))),
          byLogin.contains(UserIndexRecord(userId1, Some(email), Some(phone), Some(login))),
        )
      },
      test("create fails with UserConflict when email already exists for another user") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          result <- env.repository.create(userId2, Some(email), None, None).either
        yield assertTrue(result == Left(UserConflict))
      },
      test("create fails with UserConflict when phone already exists for another user") {
        for
          _ <- env.repository.create(userId1, None, Some(phone), None)
          result <- env.repository.create(userId2, None, Some(phone), None).either
        yield assertTrue(result == Left(UserConflict))
      },
      test("create fails with UserConflict when login already exists for another user") {
        for
          _ <- env.repository.create(userId1, None, None, Some(login))
          result <- env.repository.create(userId2, None, None, Some(login)).either
        yield assertTrue(result == Left(UserConflict))
      },
      test("create with the same id is treated as an upsert and does not conflict") {
        for
          _ <- env.repository.create(userId1, Some(email), None, None)
          _ <- env.repository.create(userId1, Some(email), Some(phone), None)
          found <- env.repository.findById(userId1)
        yield assertTrue(found.contains(UserIndexRecord(userId1, Some(email), Some(phone), None)))
      },
    )

object UserRepositorySpec:
  case class Env(repository: UserRepository)

  val testLayer: ZLayer[UserRepository, Nothing, Env] =
    ZLayer.fromFunction(Env(_))

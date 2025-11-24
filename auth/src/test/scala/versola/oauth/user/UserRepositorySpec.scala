package versola.oauth.user

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.user.UserRepository
import versola.user.model.*
import versola.util.{DatabaseSpecBase, Email, Phone}
import zio.*
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

trait UserRepositorySpec extends DatabaseSpecBase[UserRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userIds @ Seq(userId1, userId2, userId3, userId4, userId5) = Seq(
    "f077fb08-9935-4a6d-8643-bf97c073bf0f",
    "58c5fe37-6c39-43d1-ae8c-3fab32e1ed72",
    "e9b8a7d6-c543-21f0-89ab-654321098765",
    "1a2b3c4d-5e6f-7890-abcd-ef1234567890",
    "09876543-2109-8765-4321-098765432109",
  ).map(s => UserId(UUID.fromString(s)))

  val email1 = Email("user1@example.com")
  val email2 = Email("user2@example.com")

  val phone1 = Phone("+12025551234")
  val phone2 = Phone("+12025555678")

  private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

  val baseUser = UserRecord.empty(id = userId1)
    .copy(email = Some(email1))

  val user1 = baseUser

  val user2 = baseUser.copy(
    id = userId2,
    email = Some(email2),
  )

  override def testCases(env: UserRepositorySpec.Env) =
    List(
      findOrCreateTests(env),
      findTests(env),
      findByCredentialTests(env),
    )

  def findOrCreateTests(env: UserRepositorySpec.Env) =
    suite("findOrCreate")(
      test("create new user when email doesn't exist") {
        for {
          (user, wasCreated) <- env.userRepository.findOrCreate(userId1, Left(email1))
          found <- env.userRepository.find(user.id)
        } yield assertTrue(
          wasCreated,
          user.email.contains(email1),
          found.contains(user),
        )
      },
      test("return existing user when email already exists") {
        for {
          (user1, wasCreated1) <- env.userRepository.findOrCreate(userId1, Left(email1))
          (user2, wasCreated2) <- env.userRepository.findOrCreate(userId2, Left(email1))
        } yield assertTrue(
          wasCreated1,
          !wasCreated2,
          user1.id == user2.id,
          user1.email == user2.email,
          user1 == user2,
        )
      },
      test("create different users for different emails") {
        for {
          (user1, wasCreated1) <- env.userRepository.findOrCreate(userId1, Left(email1))
          (user2, wasCreated2) <- env.userRepository.findOrCreate(userId2, Left(email2))
        } yield assertTrue(
          wasCreated1,
          wasCreated2,
          user1.id != user2.id,
          user1.email.contains(email1),
          user2.email.contains(email2),
          user1.email != user2.email,
        )
      },
      test("handle concurrent creation attempts gracefully for email") {
        for {
          // Simulate concurrent creation attempts for the same email
          results <- ZIO.foreachPar(userIds)(env.userRepository.findOrCreate(_, Left(email1)))
        } yield assertTrue(
          results.map((_, wasCreated) => wasCreated).sorted == List(true, false, false, false, false).sorted,
          results.flatMap((user, _) => user.email) == List.fill(5)(email1),
        )
      },
      test("create new user when phone doesn't exist") {
        for {
          (user, wasCreated) <- env.userRepository.findOrCreate(userId1, Right(phone1))
          found <- env.userRepository.find(user.id)
        } yield assertTrue(
          wasCreated,
          user.phone.contains(phone1),
          found.contains(user),
        )
      },
      test("return existing user when phone already exists") {
        for {
          (user1, wasCreated1) <- env.userRepository.findOrCreate(userId1, Right(phone1))
          (user2, wasCreated2) <- env.userRepository.findOrCreate(userId2, Right(phone1))
        } yield assertTrue(
          wasCreated1,
          !wasCreated2,
          user1.id == user2.id,
          user1.phone == user2.phone,
          user1 == user2,
        )
      },
      test("create different users for different phones") {
        for {
          (user1, wasCreated1) <- env.userRepository.findOrCreate(userId1, Right(phone1))
          (user2, wasCreated2) <- env.userRepository.findOrCreate(userId2, Right(phone2))
        } yield assertTrue(
          wasCreated1,
          wasCreated2,
          user1.id != user2.id,
          user1.phone.contains(phone1),
          user2.phone.contains(phone2),
          user1.phone != user2.phone,
        )
      },
      test("handle concurrent creation attempts gracefully for phone") {
        for {
          // Simulate concurrent creation attempts for the same email
          results <- ZIO.foreachPar(userIds)(env.userRepository.findOrCreate(_, Right(phone1)))
        } yield assertTrue(
          results.map((_, wasCreated) => wasCreated).sorted == List(true, false, false, false, false).sorted,
          results.flatMap((user, _) => user.phone) == List.fill(5)(phone1),
        )
      },
      test("create different users for email and phone") {
        for {
          (user1, wasCreated1) <- env.userRepository.findOrCreate(userId1, Left(email1))
          (user2, wasCreated2) <- env.userRepository.findOrCreate(userId2, Right(phone1))
        } yield assertTrue(
          wasCreated1,
          wasCreated2,
          user1.id != user2.id,
          user1.email.contains(email1),
          user1.phone.isEmpty,
          user2.phone.contains(phone1),
          user2.email.isEmpty,
        )
      },
    )

  def findTests(env: UserRepositorySpec.Env) =
    suite("find")(
      test("return Some when user exists") {
        for {
          (user, _) <- env.userRepository.findOrCreate(userId1, Left(email1))
          found <- env.userRepository.find(user.id)
        } yield assertTrue(found.contains(user))
      },
      test("return None when user does not exist") {
        for {
          found <- env.userRepository.find(userId1)
        } yield assertTrue(found.isEmpty)
      },
    )

  def findByCredentialTests(env: UserRepositorySpec.Env) =
    suite("findByCredential")(
      test("return Some when user with email exists") {
        for {
          (user, _) <- env.userRepository.findOrCreate(userId1, Left(email1))
          found <- env.userRepository.findByCredential(Left(email1))
        } yield assertTrue(found.contains(user))
      },
      test("return None when user with email does not exist") {
        for {
          found <- env.userRepository.findByCredential(Left(email1))
        } yield assertTrue(found.isEmpty)
      },
      test("return Some when user with phone exists") {
        for {
          (user, _) <- env.userRepository.findOrCreate(userId1, Right(phone1))
          found <- env.userRepository.findByCredential(Right(phone1))
        } yield assertTrue(found.contains(user))
      },
      test("return None when user with phone does not exist") {
        for {
          found <- env.userRepository.findByCredential(Right(phone1))
        } yield assertTrue(found.isEmpty)
      },
    )

end UserRepositorySpec

object UserRepositorySpec:
  case class Env(userRepository: UserRepository)

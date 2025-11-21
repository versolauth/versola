package versola.user

import com.augustnagro.magnum.magzio.TransactorZIO
import com.fasterxml.uuid.{Generators, NoArgGenerator, UUIDType}
import versola.user.model.*
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

trait UserRepositorySpec extends DatabaseSpecBase[UserRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  trait TestingGenerator extends NoArgGenerator:
    def next(): Unit
    def asReal(): Unit

  val userIdGenerator = new TestingGenerator:
    private val ref = AtomicReference(newValue)
    private val generateUnique = AtomicReference(false)

    def generate() = if generateUnique.get() then newValue else ref.get()

    private def newValue = Generators.timeBasedEpochGenerator().generate()

    def next(): Unit = { ref.set(newValue); generateUnique.set(false) }
    def asReal(): Unit = generateUnique.set(true)


    override def getType = UUIDType.TIME_BASED_EPOCH


  val Seq(userId1, userId2) = Seq(
    "f077fb08-9935-4a6d-8643-bf97c073bf0f",
    "58c5fe37-6c39-43d1-ae8c-3fab32e1ed72"
  ).map(s => UserId(UUID.fromString(s)))

  val email1 = Email("user1@example.com")
  val email2 = Email("user2@example.com")

  private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

  val baseUser = UserRecord.created(
    id = userId1,
    email = email1,
  )

  val user1 = baseUser

  val user2 = baseUser.copy(
    id = userId2,
    email = Some(email2),
  )

  // Test data for update operations
  val testFirstName = FirstName("John")
  val testMiddleName = MiddleName("Michael")
  val testLastName = LastName("Doe")
  val testBirthDate = BirthDate(LocalDate.of(1990, 5, 15))

  override def testCases(env: UserRepositorySpec.Env) =
    List(
      findOrCreateByEmailTests(env),
      findTests(env),
      findByEmailTests(env),
      updateTests(env),
    )

  def findOrCreateByEmailTests(env: UserRepositorySpec.Env) =
    suite("findOrCreate")(
      test("create new user when email doesn't exist") {
        for {
          (user, wasCreated) <- env.userRepository.findOrCreateByEmail(email1)
          found <- env.userRepository.find(user.id)
        } yield assertTrue(
          wasCreated,
          user.email.contains(email1),
          found.contains(user),
        )
      },
      test("return existing user when email already exists") {
        for {
          (user1, wasCreated1) <- env.userRepository.findOrCreateByEmail(email1)
          (user2, wasCreated2) <- env.userRepository.findOrCreateByEmail(email1)
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
          (user1, wasCreated1) <- env.userRepository.findOrCreateByEmail(email1)
          _ = userIdGenerator.next()
          (user2, wasCreated2) <- env.userRepository.findOrCreateByEmail(email2)
        } yield assertTrue(
          wasCreated1,
          wasCreated2,
          user1.id != user2.id,
          user1.email.contains(email1),
          user2.email.contains(email2),
          user1.email != user2.email,
        )
      },
      test("handle concurrent creation attempts gracefully") {
        for {
          _ <- ZIO.succeed(userIdGenerator.asReal())
          // Simulate concurrent creation attempts for the same email
          results <- ZIO.collectAllPar(List.fill(5)(env.userRepository.findOrCreateByEmail(email1)))
          users = results.map(_._1)
          wasCreatedFlags = results.map(_._2)
        } yield assertTrue(
          // All users should have the same ID (only one was actually created)
          users.map(_.id).toSet.size == 1,
          // Only one should be marked as "was created"
          wasCreatedFlags.count(_ == true) == 1,
          wasCreatedFlags.count(_ == false) == 4,
          // All should have the same email
          users.forall(_.email.contains(email1)),
        )
      },
    )

  def findTests(env: UserRepositorySpec.Env) =
    suite("find")(
      test("return Some when user exists") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          found <- env.userRepository.find(user.id)
        } yield assertTrue(found.contains(user))
      },
      test("return None when user does not exist") {
        for {
          found <- env.userRepository.find(userId1)
        } yield assertTrue(found.isEmpty)
      },
    )

  def findByEmailTests(env: UserRepositorySpec.Env) =
    suite("findByEmail")(
      test("return Some when user with email exists") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          found <- env.userRepository.findByEmail(email1)
        } yield assertTrue(found.contains(user))
      },
      test("return None when user with email does not exist") {
        for {
          found <- env.userRepository.findByEmail(email1)
        } yield assertTrue(found.isEmpty)
      },
    )

  def updateTests(env: UserRepositorySpec.Env) =
    suite("update")(
      test("update single field - firstName") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          _ <- TestClock.adjust(10.seconds)
          _ <- env.userRepository.update(
            userId = user.id,
            email = None,
            firstName = Some(Some(testFirstName)),
            middleName = None,
            lastName = None,
            birthDate = None,
          )
          updated <- env.userRepository.find(user.id)
        } yield assertTrue(
          updated.isDefined,
          updated.get.firstName.contains(testFirstName),
          updated.get.middleName.isEmpty,
          updated.get.lastName.isEmpty,
          updated.get.birthDate.isEmpty,
          updated.get.updatedAt.isAfter(user.updatedAt),
        )
      },
      test("update single field - middleName") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          _ <- TestClock.adjust(10.seconds)
          _ <- env.userRepository.update(
            userId = user.id,
            email = None,
            firstName = None,
            middleName = Some(Some(testMiddleName)),
            lastName = None,
            birthDate = None,
          )
          updated <- env.userRepository.find(user.id)
        } yield assertTrue(
          updated.isDefined,
          updated.get.firstName.isEmpty,
          updated.get.middleName.contains(testMiddleName),
          updated.get.lastName.isEmpty,
          updated.get.birthDate.isEmpty,
          updated.get.updatedAt.isAfter(user.updatedAt),
        )
      },
      test("update single field - lastName") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          _ <- TestClock.adjust(10.seconds)
          _ <- env.userRepository.update(
            userId = user.id,
            email = None,
            firstName = None,
            middleName = None,
            lastName = Some(Some(testLastName)),
            birthDate = None,
          )
          updated <- env.userRepository.find(user.id)
        } yield assertTrue(
          updated.isDefined,
          updated.get.firstName.isEmpty,
          updated.get.middleName.isEmpty,
          updated.get.lastName.contains(testLastName),
          updated.get.birthDate.isEmpty,
          updated.get.updatedAt.isAfter(user.updatedAt),
        )
      },
      test("update single field - birthDate") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          _ <- TestClock.adjust(10.seconds)
          _ <- env.userRepository.update(
            userId = user.id,
            email = None,
            firstName = None,
            middleName = None,
            lastName = None,
            birthDate = Some(Some(testBirthDate)),
          )
          updated <- env.userRepository.find(user.id)
        } yield assertTrue(
          updated.isDefined,
          updated.get.firstName.isEmpty,
          updated.get.middleName.isEmpty,
          updated.get.lastName.isEmpty,
          updated.get.birthDate.contains(testBirthDate),
          updated.get.updatedAt.isAfter(user.updatedAt),
        )
      },
      test("update multiple fields at once") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          _ <- TestClock.adjust(10.seconds)
          _ <- env.userRepository.update(
            userId = user.id,
            email = None,
            firstName = Some(Some(testFirstName)),
            middleName = Some(Some(testMiddleName)),
            lastName = Some(Some(testLastName)),
            birthDate = Some(Some(testBirthDate)),
          )
          updated <- env.userRepository.find(user.id)
        } yield assertTrue(
          updated.isDefined,
          updated.get.firstName.contains(testFirstName),
          updated.get.middleName.contains(testMiddleName),
          updated.get.lastName.contains(testLastName),
          updated.get.birthDate.contains(testBirthDate),
          updated.get.updatedAt.isAfter(user.updatedAt),
        )
      },
      test("clear field by setting to None") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          _ <- env.userRepository.update(
            userId = user.id,
            email = None,
            firstName = Some(Some(testFirstName)),
            middleName = Some(Some(testMiddleName)),
            lastName = None,
            birthDate = None,
          )
          _ <- env.userRepository.update(
            userId = user.id,
            email = None,
            firstName = Some(None),
            middleName = None,
            lastName = None,
            birthDate = None,
          )
          updated <- env.userRepository.find(user.id)
        } yield assertTrue(
          updated.isDefined,
          updated.get.firstName.isEmpty,
          updated.get.middleName.contains(testMiddleName),
        )
      },
      test("update with no changes should not modify updated_at") {
        for {
          (user, _) <- env.userRepository.findOrCreateByEmail(email1)
          _ <- TestClock.adjust(10.seconds)
          originalUpdatedAt = user.updatedAt
          _ <- env.userRepository.update(
            userId = user.id,
            email = None,
            firstName = None,
            middleName = None,
            lastName = None,
            birthDate = None,
          )
          updated <- env.userRepository.find(user.id)
        } yield assertTrue(
          updated.isDefined,
          updated.get.updatedAt == originalUpdatedAt, // Should not change
        )
      },
      test("update non-existent user should not fail") {
        for {
          _ <- env.userRepository.update(
            userId = userId1,
            email = None,
            firstName = Some(Some(testFirstName)),
            middleName = None,
            lastName = None,
            birthDate = None,
          )
          found <- env.userRepository.find(userId1)
        } yield assertTrue(found.isEmpty) // Should still not exist
      },
    )

end UserRepositorySpec

object UserRepositorySpec:
  case class Env(userRepository: UserRepository)

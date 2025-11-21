package versola.user

import versola.user.model.*
import versola.util.UnitSpecBase
import zio.*
import zio.internal.stacktracer.SourceLocation
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object UserServiceSpec extends UnitSpecBase:

  // Test data
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val userId2 = UserId(UUID.fromString("58c5fe37-6c39-43d1-ae8c-3fab32e1ed72"))
  val email1 = Email("test@example.com")
  val now = Instant.now()

  val baseUser = UserRecord(
    id = userId1,
    email = Some(email1),
    firstName = Some(FirstName("John")),
    middleName = Some(MiddleName("Michael")),
    lastName = Some(LastName("Doe")),
    birthDate = Some(BirthDate(LocalDate.of(1990, 1, 1))),
    updatedAt = now,
  )

  val emptyUser = UserRecord.created(userId2, email1)

  class Env:
    val userRepository = stub[UserRepository]
    val userService = UserService.Impl(userRepository)

  val spec = suite("UserService")(
    suite("getProfile") {
      case class Verify(
          expectedFindCall: Option[UserId] = None,
          expectedResult: Either[UserNotFound, UserResponse] = Left(UserNotFound(userId1)),
      )

      def testCase(
          description: String,
          foundUser: Option[UserRecord],
          verify: Verify,
          userId: UserId = userId1,
      )(using
          SourceLocation,
          Trace,
      ) = test(description) {
        val env = Env()
        for
          _ <- env.userRepository.find.succeedsWith(foundUser)
          result <- env.userService.getProfile(userId).either
          findCalls = env.userRepository.find.calls
        yield assertTrue(
          findCalls == verify.expectedFindCall.toList,
          result == verify.expectedResult,
        )
      }

      List(
        testCase(
          description = "return user profile when user exists",
          foundUser = Some(baseUser),
          verify = Verify(
            expectedFindCall = Some(userId1),
            expectedResult = Right(UserResponse.from(baseUser)),
          ),
        ),
        testCase(
          description = "return UserNotFound when user does not exist",
          foundUser = None,
          verify = Verify(
            expectedFindCall = Some(userId1),
            expectedResult = Left(UserNotFound(userId1)),
          ),
        ),
        testCase(
          description = "return user profile for different user",
          foundUser = Some(emptyUser),
          verify = Verify(
            expectedFindCall = Some(userId2),
            expectedResult = Right(UserResponse.from(emptyUser)),
          ),
          userId = userId2,
        ),
        test("fail when repository throws exception") {
          val env = Env()
          val exception = RuntimeException("Database connection failed")
          for
            _ <- env.userRepository.find.failsWith(exception)
            result <- env.userService.getProfile(userId1).either
            findCalls = env.userRepository.find.calls
          yield assertTrue(
            findCalls == List(userId1),
            result.isLeft,
            result.left.getOrElse(null) == exception,
          )
        }
      )
    },
    suite("updateProfile") {
      case class Verify(
          expectedUpdateCall: Option[(
              UserId,
              Option[Option[Email]],
              Option[Option[FirstName]],
              Option[Option[MiddleName]],
              Option[Option[LastName]],
              Option[Option[BirthDate]],
          )] = None,
          expectedErrors: PatchUserRequest.UpdateFields = PatchUserRequest.UpdateFields.empty,
      )

      def testCase(
          description: String,
          foundUser: Option[UserRecord],
          request: PatchUserRequest,
          verify: Verify,
          userId: UserId = userId1,
      )(using
          SourceLocation,
          Trace,
      ) = test(description) {
        val env = Env()
        for
          _ <- env.userRepository.find.succeedsWith(foundUser)
          _ <- env.userRepository.update.succeedsWith(())
          result <- env.userService.updateProfile(userId, request)
          updateCalls = env.userRepository.update.calls
        yield assertTrue(
          updateCalls == verify.expectedUpdateCall.toList,
          result.errors == verify.expectedErrors,
        )
      }

      List(
        testCase(
          description = "return empty response when user does not exist",
          foundUser = None,
          request = PatchUserRequest(Set.empty, PatchUserRequest.UpdateFields.empty),
          verify = Verify(),
        ),
        testCase(
          description = "update firstName successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update = PatchUserRequest.UpdateFields.empty.copy(
              firstName = Some("Jane"),
            ),
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, Some(Some(FirstName("Jane"))), None, None, None)),
          ),
        ),
        testCase(
          description = "update lastName successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update =
              PatchUserRequest.UpdateFields.empty.copy(
                lastName = Some("Smith"),
              ),
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, None, Some(Some(LastName("Smith"))), None)),
          ),
        ),
        testCase(
          description = "update middleName successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update = PatchUserRequest.UpdateFields.empty.copy(
              middleName = Some("Alexander"),
            ),
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, Some(Some(MiddleName("Alexander"))), None, None)),
          ),
        ),
        testCase(
          description = "update birthDate successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update =
              PatchUserRequest.UpdateFields(firstName = None, middleName = None, lastName = None, birthDate = Some("1995-05-15")),
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, None, None, Some(Some(BirthDate(LocalDate.of(1995, 5, 15)))))),
          ),
        ),
        testCase(
          description = "update multiple fields successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update = PatchUserRequest.UpdateFields(
              firstName = Some("Jane"),
              middleName = None,
              lastName = Some("Smith"),
              birthDate = None,
            ),
          ),
          verify = Verify(
            expectedUpdateCall =
              Some((userId1, None, Some(Some(FirstName("Jane"))), None, Some(Some(LastName("Smith"))), None)),
          ),
        ),

        // Delete fields
        testCase(
          description = "delete firstName successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set(FieldName.firstName),
            update = PatchUserRequest.UpdateFields.empty,
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, Some(None), None, None, None)),
          ),
        ),
        testCase(
          description = "delete multiple fields successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set(FieldName.firstName, FieldName.birthDate),
            update = PatchUserRequest.UpdateFields.empty,
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, Some(None), None, None, Some(None))),
          ),
        ),
        testCase(
          description = "return error for invalid firstName",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update = PatchUserRequest.UpdateFields(
              firstName = Some("A"),
              middleName = None,
              lastName = None,
              birthDate = None,
            ), // Too short
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, None, None, None)),
            expectedErrors = PatchUserRequest.UpdateFields(
              firstName = Some("A is invalid name part"),
              middleName = None,
              lastName = None,
              birthDate = None,
            ),
          ),
        ),
        testCase(
          description = "return error for invalid lastName",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update = PatchUserRequest.UpdateFields.empty.copy(
              lastName = Some("Smith123"),
            ),
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, None, None, None)),
            expectedErrors = PatchUserRequest.UpdateFields(
              firstName = None,
              middleName = None,
              lastName = Some("Smith123 is invalid name part"),
              birthDate = None,
            ),
          ),
        ),
        testCase(
          description = "return error for invalid middleName",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update = PatchUserRequest.UpdateFields.empty.copy(
              middleName = Some("X"),
            ), // Too short
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, None, None, None)),
            expectedErrors = PatchUserRequest.UpdateFields(
              firstName = None,
              middleName = Some("X is invalid name part"),
              lastName = None,
              birthDate = None,
            ),
          ),
        ),
        testCase(
          description = "return error for invalid birthDate",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update =
              PatchUserRequest.UpdateFields(firstName = None, middleName = None, lastName = None, birthDate = Some("not-a-date")),
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, None, None, None)),
            expectedErrors = PatchUserRequest.UpdateFields(
              firstName = None,
              middleName = None,
              lastName = None,
              birthDate = Some("not-a-date is invalid birth date"),
            ),
          ),
        ),
        testCase(
          description = "return multiple validation errors",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set.empty,
            update = PatchUserRequest.UpdateFields(
              firstName = Some("A"),
              middleName = None,
              lastName = Some("Smith123"),
              birthDate = None,
            ),
          ),
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, None, None, None)),
            expectedErrors = PatchUserRequest.UpdateFields(
              firstName = Some("A is invalid name part"),
              middleName = None,
              lastName = Some("Smith123 is invalid name part"),
              birthDate = None,
            ),
          ),
        ),
        testCase(
          description = "mix updates and deletes successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest(
            delete = Set(FieldName.middleName, FieldName.birthDate),
            update = PatchUserRequest.UpdateFields(
              firstName = Some("Jane"),
              middleName = None,
              lastName = None,
              birthDate = None,
            ),
          ),
          verify = Verify(
            expectedUpdateCall =
              Some((userId1, None, Some(Some(FirstName("Jane"))), Some(None), None, Some(None))),
          ),
        ),
        testCase(
          description = "handle empty request successfully",
          foundUser = Some(baseUser),
          request = PatchUserRequest.empty,
          verify = Verify(
            expectedUpdateCall = Some((userId1, None, None, None, None, None)),
          ),
        ),
      )
    },
  )

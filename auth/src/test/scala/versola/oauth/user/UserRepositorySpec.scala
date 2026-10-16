package versola.oauth.user

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.UserRepository
import versola.user.model.*
import versola.util.{DatabaseSpecBase, Email, Phone}
import zio.*
import zio.json.ast.Json
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
      findTests(env),
      findByCredentialTests(env),
      roleTests(env),
      registerTests(env),
      claimsTests(env),
      deleteTests(env),
      markVerifiedTests(env),
    )

  private def seed(
      env: UserRepositorySpec.Env,
      id: UserId,
      email: Option[Email] = None,
      phone: Option[Phone] = None,
  ) =
    env.userRepository.upsert(id, UUID.randomUUID(), email, phone, None)

  def findTests(env: UserRepositorySpec.Env) =
    suite("find")(
      test("return Some when user exists") {
        for {
          _ <- seed(env, userId1, email = Some(email1))
          found <- env.userRepository.find(userId1)
        } yield assertTrue(found.exists(_.email.contains(email1)))
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
          _ <- seed(env, userId1, email = Some(email1))
          found <- env.userRepository.findByCredential(Left(email1))
        } yield assertTrue(found.exists(_.id == userId1))
      },
      test("return None when user with email does not exist") {
        for {
          found <- env.userRepository.findByCredential(Left(email1))
        } yield assertTrue(found.isEmpty)
      },
      test("return Some when user with phone exists") {
        for {
          _ <- seed(env, userId1, phone = Some(phone1))
          found <- env.userRepository.findByCredential(Right(phone1))
        } yield assertTrue(found.exists(_.id == userId1))
      },
      test("return None when user with phone does not exist") {
        for {
          found <- env.userRepository.findByCredential(Right(phone1))
        } yield assertTrue(found.isEmpty)
      },
    )

  def roleTests(env: UserRepositorySpec.Env) =
    suite("roles")(
      test("add roles to user") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-a"), RoleId("role-b")), Set.empty)
          roles <- env.userRepository.findRolesByUserAndTenant(userId1, TenantId("tenant-1"))
        yield assertTrue(roles.toSet == Set(RoleId("role-a"), RoleId("role-b")))
      },
      test("remove roles from user") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-a"), RoleId("role-b")), Set.empty)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set.empty, Set(RoleId("role-a")))
          roles <- env.userRepository.findRolesByUserAndTenant(userId1, TenantId("tenant-1"))
        yield assertTrue(roles == List(RoleId("role-b")))
      },
      test("add and remove roles in one call") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-a"), RoleId("role-b")), Set.empty)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-c")), Set(RoleId("role-a")))
          roles <- env.userRepository.findRolesByUserAndTenant(userId1, TenantId("tenant-1"))
        yield assertTrue(roles.toSet == Set(RoleId("role-b"), RoleId("role-c")))
      },
      test("empty add and remove is a no-op") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-a")), Set.empty)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set.empty, Set.empty)
          roles <- env.userRepository.findRolesByUserAndTenant(userId1, TenantId("tenant-1"))
        yield assertTrue(roles == List(RoleId("role-a")))
      },
      test("insert is idempotent via ON CONFLICT DO NOTHING") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-a")), Set.empty)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-a")), Set.empty)
          roles <- env.userRepository.findRolesByUserAndTenant(userId1, TenantId("tenant-1"))
        yield assertTrue(roles == List(RoleId("role-a")))
      },
      test("remove non-existing role is a no-op") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-a")), Set.empty)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set.empty, Set(RoleId("role-b")))
          roles <- env.userRepository.findRolesByUserAndTenant(userId1, TenantId("tenant-1"))
        yield assertTrue(roles == List(RoleId("role-a")))
      },
    )

  def registerTests(env: UserRepositorySpec.Env) =
    suite("register")(
      test("creates the account and grants the tenant roles") {
        for
          user <- env.userRepository.register(userId1, Left(email1), TenantId("tenant-1"), Set(RoleId("role-a")))
          found <- env.userRepository.find(user.id)
          roles <- env.userRepository.findRolesByUserAndTenant(user.id, TenantId("tenant-1"))
        yield assertTrue(
          user.email == Some(email1),
          found.map(_.id) == Some(user.id),
          roles == List(RoleId("role-a")),
        )
      },
      test("creates the account from a phone credential") {
        for
          user <- env.userRepository.register(userId1, Right(phone1), TenantId("tenant-1"), Set.empty)
          found <- env.userRepository.find(user.id)
        yield assertTrue(user.phone == Some(phone1), found.flatMap(_.phone) == Some(phone1))
      },
      test("returns the existing account when the credential was already claimed") {
        for
          first <- env.userRepository.register(userId1, Left(email1), TenantId("tenant-1"), Set.empty)
          second <- env.userRepository.register(userId2, Left(email1), TenantId("tenant-1"), Set.empty)
        yield assertTrue(first.id == second.id, second.id == userId1)
      },
      test("grants no roles when the registration flow configures none") {
        for
          user <- env.userRepository.register(userId1, Left(email1), TenantId("tenant-1"), Set.empty)
          roles <- env.userRepository.findRolesByUser(user.id)
        yield assertTrue(roles.isEmpty)
      },
      test("re-registering with the same roles is idempotent") {
        for
          _ <- env.userRepository.register(userId1, Left(email1), TenantId("tenant-1"), Set(RoleId("role-a")))
          _ <- env.userRepository.register(userId1, Left(email1), TenantId("tenant-1"), Set(RoleId("role-a")))
          roles <- env.userRepository.findRolesByUserAndTenant(userId1, TenantId("tenant-1"))
        yield assertTrue(roles == List(RoleId("role-a")))
      },
      test("findRolesByUser groups the roles by tenant") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-1"), Set(RoleId("role-a"), RoleId("role-b")), Set.empty)
          _ <- env.userRepository.updateRoles(userId1, TenantId("tenant-2"), Set(RoleId("role-c")), Set.empty)
          roles <- env.userRepository.findRolesByUser(userId1)
        yield assertTrue(
          roles.keySet == Set(TenantId("tenant-1"), TenantId("tenant-2")),
          roles(TenantId("tenant-1")).toSet == Set(RoleId("role-a"), RoleId("role-b")),
          roles(TenantId("tenant-2")) == List(RoleId("role-c")),
        )
      },
      test("findRolesByUser returns nothing for a user with no roles") {
        for
          _ <- seed(env, userId1)
          roles <- env.userRepository.findRolesByUser(userId1)
        yield assertTrue(roles.isEmpty)
      },
      test("findByLogin returns the account holding that login") {
        for
          _ <- env.userRepository.upsert(userId1, UUID.randomUUID(), None, None, Some(Login("someone")))
          found <- env.userRepository.findByLogin(Login("someone"))
          missing <- env.userRepository.findByLogin(Login("nobody"))
        yield assertTrue(found.map(_.id) == Some(userId1), missing.isEmpty)
      },
    )

  def claimsTests(env: UserRepositorySpec.Env) =
    suite("patchClaims")(
      test("merges the patch into the stored claims") {
        for
          _ <- seed(env, userId1, email = Some(email1))
          _ <- env.userRepository.patchClaims(userId1, Json.Obj("name" -> Json.Str("Ada")))
          _ <- env.userRepository.patchClaims(userId1, Json.Obj("locale" -> Json.Str("en")))
          found <- env.userRepository.find(userId1)
        yield assertTrue(
          found.map(_.claims) == Some(Json.Obj("name" -> Json.Str("Ada"), "locale" -> Json.Str("en"))),
        )
      },
      test("a null value removes the claim rather than storing null") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.patchClaims(userId1, Json.Obj("name" -> Json.Str("Ada")))
          _ <- env.userRepository.patchClaims(userId1, Json.Obj("name" -> Json.Null))
          found <- env.userRepository.find(userId1)
        yield assertTrue(found.map(_.claims) == Some(Json.Obj()))
      },
      test("patching an unknown user is a no-op") {
        for
          _ <- env.userRepository.patchClaims(userId1, Json.Obj("name" -> Json.Str("Ada")))
          found <- env.userRepository.find(userId1)
        yield assertTrue(found.isEmpty)
      },
    )

  def deleteTests(env: UserRepositorySpec.Env) =
    suite("delete")(
      test("removes the account") {
        for
          _ <- seed(env, userId1, email = Some(email1))
          _ <- env.userRepository.delete(userId1)
          found <- env.userRepository.find(userId1)
        yield assertTrue(found.isEmpty)
      },
      test("deleting an unknown user is a no-op") {
        for
          _ <- seed(env, userId1)
          _ <- env.userRepository.delete(userId2)
          found <- env.userRepository.find(userId1)
        yield assertTrue(found.nonEmpty)
      },
    )

  /** Whether the user's `claims` carry `claim: true`. */
  private def isVerified(user: Option[UserRecord], claim: String): Boolean =
    user.exists(_.claims.fields.toMap.get(claim).contains(zio.json.ast.Json.Bool(true)))

  def markVerifiedTests(env: UserRepositorySpec.Env) =
    suite("markVerified")(
      test("sets the email_verified claim to true after successful email OTP") {
        for
          _ <- seed(env, userId1, email = Some(email1))
          before <- env.userRepository.find(userId1)
          _ <- env.userRepository.markVerified(userId1, Left(email1))
          after <- env.userRepository.find(userId1)
        yield assertTrue(
          !isVerified(before, "email_verified"),
          isVerified(after, "email_verified"),
        )
      },
      test("sets the phone_number_verified claim to true after successful phone OTP") {
        for
          _ <- seed(env, userId1, phone = Some(phone1))
          before <- env.userRepository.find(userId1)
          _ <- env.userRepository.markVerified(userId1, Right(phone1))
          after <- env.userRepository.find(userId1)
        yield assertTrue(
          !isVerified(before, "phone_number_verified"),
          isVerified(after, "phone_number_verified"),
        )
      },
      test("is idempotent: calling twice leaves the flag true") {
        for
          _ <- seed(env, userId1, email = Some(email1))
          _ <- env.userRepository.markVerified(userId1, Left(email1))
          _ <- env.userRepository.markVerified(userId1, Left(email1))
          after <- env.userRepository.find(userId1)
        yield assertTrue(isVerified(after, "email_verified"))
      },
      test("does not set the flag when the stored email differs from the credential") {
        for
          _ <- seed(env, userId1, email = Some(email1))
          _ <- env.userRepository.markVerified(userId1, Left(email2))
          after <- env.userRepository.find(userId1)
        yield assertTrue(!isVerified(after, "email_verified"))
      },
      // upsert only applies a version newer than the stored one (UUIDs compare bytewise), so these
      // tests use fixed, ordered versions: a random one would be skipped about half the time.
      test("upsert resets email_verified when email changes") {
        val older = UUID(0L, 1L)
        val newer = UUID(0L, 2L)
        for
          _ <- env.userRepository.upsert(userId1, older, Some(email1), None, None)
          _ <- env.userRepository.markVerified(userId1, Left(email1))
          verified <- env.userRepository.find(userId1)
          _ <- env.userRepository.upsert(userId1, newer, Some(email2), None, None)
          afterChange <- env.userRepository.find(userId1)
        yield assertTrue(
          isVerified(verified, "email_verified"),
          afterChange.exists(_.email.contains(email2)),
          !isVerified(afterChange, "email_verified"),
        )
      },
      test("verifying keeps the other claims the user already has") {
        for
          _ <- seed(env, userId1, email = Some(email1))
          _ <- env.userRepository.patchClaims(userId1, zio.json.ast.Json.Obj("name" -> zio.json.ast.Json.Str("Ann")))
          _ <- env.userRepository.markVerified(userId1, Left(email1))
          after <- env.userRepository.find(userId1)
        yield assertTrue(
          isVerified(after, "email_verified"),
          after.exists(_.claims.fields.toMap.get("name").contains(zio.json.ast.Json.Str("Ann"))),
        )
      },
      test("upsert resets phone_number_verified when the phone changes and leaves the email claim alone") {
        val older = UUID(0L, 1L)
        val newer = UUID(0L, 2L)
        for
          _ <- env.userRepository.upsert(userId1, older, Some(email1), Some(phone1), None)
          _ <- env.userRepository.markVerified(userId1, Left(email1))
          _ <- env.userRepository.markVerified(userId1, Right(phone1))
          _ <- env.userRepository.upsert(userId1, newer, Some(email1), Some(phone2), None)
          after <- env.userRepository.find(userId1)
        yield assertTrue(
          after.exists(_.phone.contains(phone2)),
          !isVerified(after, "phone_number_verified"),
          isVerified(after, "email_verified"),
        )
      },
      test("upsert preserves email_verified when email is unchanged") {
        val older = UUID(0L, 1L)
        val newer = UUID(0L, 2L)
        for
          _ <- env.userRepository.upsert(userId1, older, Some(email1), None, None)
          _ <- env.userRepository.markVerified(userId1, Left(email1))
          _ <- env.userRepository.upsert(userId1, newer, Some(email1), None, Some(Login("someone")))
          after <- env.userRepository.find(userId1)
        yield assertTrue(
          after.exists(_.login.contains(Login("someone"))),
          isVerified(after, "email_verified"),
        )
      },
    )

end UserRepositorySpec

object UserRepositorySpec:
  case class Env(userRepository: UserRepository)

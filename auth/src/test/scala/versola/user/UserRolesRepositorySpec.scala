package versola.user

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.model.UserId
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.util.UUID

trait UserRolesRepositorySpec extends DatabaseSpecBase[UserRolesRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))

  val tenantId1 = TenantId("tenant-1")

  val roleA = RoleId("role-a")
  val roleB = RoleId("role-b")
  val roleC = RoleId("role-c")

  override def testCases(env: UserRolesRepositorySpec.Env): List[Spec[UserRolesRepositorySpec.Env & Scope, Any]] =
    List(
      test("add roles to user") {
        for
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set(roleA, roleB), remove = Set.empty)
          roles <- env.rolesRepository.findRolesByUserAndTenant(userId1, tenantId1)
        yield assertTrue(roles.toSet == Set(roleA, roleB))
      },
      test("remove roles from user") {
        for
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set(roleA, roleB), remove = Set.empty)
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set.empty, remove = Set(roleA))
          roles <- env.rolesRepository.findRolesByUserAndTenant(userId1, tenantId1)
        yield assertTrue(roles == List(roleB))
      },
      test("add and remove roles in one call") {
        for
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set(roleA, roleB), remove = Set.empty)
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set(roleC), remove = Set(roleA))
          roles <- env.rolesRepository.findRolesByUserAndTenant(userId1, tenantId1)
        yield assertTrue(roles.toSet == Set(roleB, roleC))
      },
      test("empty add and remove is a no-op") {
        for
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set(roleA), remove = Set.empty)
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set.empty, remove = Set.empty)
          roles <- env.rolesRepository.findRolesByUserAndTenant(userId1, tenantId1)
        yield assertTrue(roles == List(roleA))
      },
      test("insert is idempotent via ON CONFLICT DO NOTHING") {
        for
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set(roleA), remove = Set.empty)
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set(roleA), remove = Set.empty)
          roles <- env.rolesRepository.findRolesByUserAndTenant(userId1, tenantId1)
        yield assertTrue(roles == List(roleA))
      },
      test("remove non-existing role is a no-op") {
        for
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set(roleA), remove = Set.empty)
          _ <- env.rolesRepository.updateRoles(userId1, tenantId1, add = Set.empty, remove = Set(roleB))
          roles <- env.rolesRepository.findRolesByUserAndTenant(userId1, tenantId1)
        yield assertTrue(roles == List(roleA))
      },
    )

object UserRolesRepositorySpec:
  case class Env(rolesRepository: UserRolesRepository)

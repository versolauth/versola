package versola.central.configuration.roles

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.permissions.Permission
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchDescription, PatchPermissions}
import versola.util.DatabaseSpecBase
import zio.prelude.EqualOps
import zio.test.*

trait RoleRepositorySpec extends DatabaseSpecBase[RoleRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenantId = TenantId("tenant-a")
  val roleId = RoleId("admin")
  val readPermission = Permission("users:read")
  val writePermission = Permission("users:write")

  override def testCases(env: RoleRepositorySpec.Env) =
    List(
      test("create and find role") {
        for
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission), active = true)
          found <- env.repository.findRole(tenantId, roleId)
        yield assertTrue(
          found === Some(
            RoleRecord(
              id = roleId,
              tenantId = tenantId,
              description = Map("en" -> "Administrators"),
              permissions = Set(readPermission),
              active = true,
            ),
          ),
        )
      },
      test("create an inactive role") {
        for
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission), active = false)
          found <- env.repository.findRole(tenantId, roleId)
        yield assertTrue(found.exists(!_.active))
      },
      test("update role description and permissions") {
        for
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission), active = true)
          _ <- env.repository.updateRole(
            tenantId,
            roleId,
            PatchDescription(
              add = Map("ru" -> "Администраторы"),
              delete = Set.empty,
            ),
            PatchPermissions(
              add = Set(writePermission),
              remove = Set(readPermission),
            ),
            active = None,
          )
          found <- env.repository.findRole(tenantId, roleId)
        yield assertTrue(
          found === Some(
            RoleRecord(
              id = roleId,
              tenantId = tenantId,
              description = Map("en" -> "Administrators", "ru" -> "Администраторы"),
              permissions = Set(writePermission),
              active = true,
            ),
          ),
        )
      },
      test("update role active flag, leaving description and permissions alone") {
        for
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission), active = true)
          _ <-
            env.repository.updateRole(tenantId, roleId, PatchDescription(Map.empty, Set.empty), PatchPermissions(Set.empty, Set.empty), Some(false))
          deactivated <- env.repository.findRole(tenantId, roleId)
          _ <- env.repository.updateRole(tenantId, roleId, PatchDescription(Map.empty, Set.empty), PatchPermissions(Set.empty, Set.empty), Some(true))
          reactivated <- env.repository.findRole(tenantId, roleId)
        yield assertTrue(
          deactivated === Some(RoleRecord(roleId, tenantId, Map("en" -> "Administrators"), Set(readPermission), active = false)),
          reactivated.exists(_.active),
        )
      },
      test("update without an active patch keeps an inactive role inactive") {
        for
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission), active = false)
          _ <-
            env.repository.updateRole(tenantId, roleId, PatchDescription(Map("ru" -> "А"), Set.empty), PatchPermissions(Set.empty, Set.empty), None)
          found <- env.repository.findRole(tenantId, roleId)
        yield assertTrue(found.exists(!_.active), found.exists(_.description.contains("ru")))
      },
      test("mark role inactive and then delete it") {
        for
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission), active = true)
          _ <- env.repository.markRoleInactive(tenantId, roleId)
          inactive <- env.repository.findRole(tenantId, roleId)
          _ <- env.repository.deleteRole(tenantId, roleId)
          deleted <- env.repository.findRole(tenantId, roleId)
        yield assertTrue(
          inactive.exists(!_.active),
          deleted.isEmpty,
        )
      },
    )

object RoleRepositorySpec:
  case class Env(repository: RoleRepository)

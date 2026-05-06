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
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission))
          found <- env.repository.findRole(tenantId, roleId)
        yield assertTrue(
          found === Some(
            RoleRecord(
              id = roleId,
              tenantId = tenantId,
              description = Map("en" -> "Administrators"),
              permissions = Set(readPermission),
              active = true,
            )
          )
        )
      },
      test("update role description and permissions") {
        for
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission))
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
            )
          )
        )
      },
      test("mark role inactive and then delete it") {
        for
          _ <- env.repository.createRole(tenantId, roleId, Map("en" -> "Administrators"), List(readPermission))
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
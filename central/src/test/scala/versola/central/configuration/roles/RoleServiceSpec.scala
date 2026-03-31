package versola.central.configuration.roles

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.permissions.Permission
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateRoleRequest, PatchDescription, PatchPermissions, UpdateRoleRequest}
import versola.util.ReloadingCache
import zio.prelude.EqualOps
import zio.*
import zio.test.*

object RoleServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val adminRoleId = RoleId("admin")
  private val operatorRoleId = RoleId("operator")
  private val readPermission = Permission("users:read")
  private val writePermission = Permission("users:write")

  private val adminRole = RoleRecord(adminRoleId, tenantId, Map("en" -> "Admin"), Set(readPermission), active = true)
  private val operatorRole = RoleRecord(operatorRoleId, tenantId, Map("en" -> "Operator"), Set(writePermission), active = false)
  private val otherTenantRole = RoleRecord(RoleId("viewer"), otherTenantId, Map("en" -> "Viewer"), Set(readPermission), active = true)

  private val createRequest = CreateRoleRequest(
    tenantId = tenantId,
    id = adminRoleId,
    description = Map("en" -> "Admin"),
    permissions = Set(readPermission, writePermission),
  )

  private val updateRequest = UpdateRoleRequest(
    tenantId = tenantId,
    id = adminRoleId,
    description = PatchDescription(add = Map("ru" -> "Админ"), delete = Set.empty),
    permissions = PatchPermissions(add = Set(writePermission), remove = Set(readPermission)),
  )

  class Env(initial: Vector[RoleRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[RoleRepository]
    val service = RoleService.Impl(cache, repository)

  def spec = suite("RoleService")(
    test("getTenantRoles filters cache by tenant") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        result <- env.service.getTenantRoles(tenantId)
      yield assertTrue(result === Vector(adminRole))
    },
    test("getTenantRoles applies pagination after filtering") {
      val env = new Env(Vector(adminRole, operatorRole, otherTenantRole))

      for
        result <- env.service.getTenantRoles(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result === Vector(operatorRole))
    },
    test("createRole delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.createRole.succeedsWith(())
        _ <- env.service.createRole(createRequest)
      yield assertTrue(
        env.repository.createRole.calls === List((tenantId, adminRoleId, createRequest.description, createRequest.permissions.toList))
      )
    },
    test("updateRole delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.updateRole.succeedsWith(())
        _ <- env.service.updateRole(updateRequest)
      yield assertTrue(
        env.repository.updateRole.calls == List((tenantId, adminRoleId, updateRequest.description, updateRequest.permissions))
      )
    },
    test("markRoleInactive delegates tenant and id to repository") {
      val env = new Env()

      for
        _ <- env.repository.markRoleInactive.succeedsWith(())
        _ <- env.service.markRoleInactive(tenantId, adminRoleId)
      yield assertTrue(env.repository.markRoleInactive.calls === List((tenantId, adminRoleId)))
    },
    test("deleteRole delegates tenant and id to repository") {
      val env = new Env()

      for
        _ <- env.repository.deleteRole.succeedsWith(())
        _ <- env.service.deleteRole(tenantId, adminRoleId)
      yield assertTrue(env.repository.deleteRole.calls === List((tenantId, adminRoleId)))
    },
    test("sync removes cached role on delete event") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        _ <- env.service.sync(SyncEvent.RolesUpdated(tenantId, adminRoleId, SyncEvent.Op.DELETE))
        cached <- env.cache.get
      yield assertTrue(cached === Vector(otherTenantRole))
    },
    test("sync upserts fetched role for non-delete event") {
      val env = new Env(Vector(adminRole, otherTenantRole))
      val updatedRole = adminRole.copy(description = Map("en" -> "Updated admin"), permissions = Set(readPermission, writePermission))

      for
        _ <- env.repository.findRole.succeedsWith(Some(updatedRole))
        _ <- env.service.sync(SyncEvent.RolesUpdated(tenantId, adminRoleId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findRole.calls === List((tenantId, adminRoleId)),
        cached === Vector(updatedRole, otherTenantRole),
      )
    },
    test("sync removes cached role when record is missing on non-delete event") {
      val env = new Env(Vector(adminRole, otherTenantRole))

      for
        _ <- env.repository.findRole.succeedsWith(None)
        _ <- env.service.sync(SyncEvent.RolesUpdated(tenantId, adminRoleId, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findRole.calls === List((tenantId, adminRoleId)),
        cached === Vector(otherTenantRole),
      )
    },
  )
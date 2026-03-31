package versola.central.configuration.permissions

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.resources.ResourceEndpointId
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreatePermissionRequest, PatchDescription, UpdatePermissionRequest}
import versola.util.ReloadingCache
import zio.prelude.EqualOps
import zio.*
import zio.test.*

import java.util.UUID

object PermissionServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private def endpointId(value: String): ResourceEndpointId = ResourceEndpointId(UUID.fromString(value))

  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val readListEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000101")
  private val readDetailEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000102")
  private val readManagedEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000103")
  private val writeUpdateEndpointId = endpointId("018f0f2a-1c7b-7000-8000-000000000201")
  private val tenantPermission = PermissionRecord(Some(tenantId), Permission("users:read"), Map("en" -> "Read users"), Set(readListEndpointId))
  private val globalPermission = PermissionRecord(None, Permission("admin:view"), Map("en" -> "View admin"), Set.empty)
  private val otherTenantPermission = PermissionRecord(Some(otherTenantId), Permission("users:write"), Map("en" -> "Write users"), Set(writeUpdateEndpointId))

  private val createRequest = CreatePermissionRequest(
    tenantId = Some(tenantId),
    permission = tenantPermission.id,
    description = tenantPermission.description,
    endpointIds = tenantPermission.endpointIds,
  )

  private val updateRequest = UpdatePermissionRequest(
    tenantId = Some(tenantId),
    permission = tenantPermission.id,
    description = PatchDescription(add = Map("ru" -> "Чтение пользователей"), delete = Set.empty),
    endpointIds = Some(Set(readListEndpointId, readDetailEndpointId)),
  )

  class Env(initial: Vector[PermissionRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[PermissionRepository]
    val service = PermissionService.Impl(cache, repository)

  def spec = suite("PermissionService")(
    test("getTenantPermissions returns global and tenant-specific permissions") {
      val env = new Env(Vector(globalPermission, tenantPermission, otherTenantPermission))

      for
        result <- env.service.getTenantPermissions(tenantId)
      yield assertTrue(result === Vector(globalPermission, tenantPermission))
    },
    test("getTenantPermissions applies pagination after filtering") {
      val env = new Env(Vector(globalPermission, tenantPermission, otherTenantPermission))

      for
        result <- env.service.getTenantPermissions(tenantId, offset = 1, limit = Some(1))
      yield assertTrue(result === Vector(tenantPermission))
    },
    test("createPermission delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.createPermission.succeedsWith(())
        _ <- env.service.createPermission(createRequest)
      yield assertTrue(
        env.repository.createPermission.calls === List((Some(tenantId), tenantPermission.id, tenantPermission.description, tenantPermission.endpointIds))
      )
    },
    test("updatePermission delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.updatePermission.succeedsWith(())
        _ <- env.service.updatePermission(updateRequest)
      yield assertTrue(
        env.repository.updatePermission.calls == List((Some(tenantId), tenantPermission.id, updateRequest.description, updateRequest.endpointIds))
      )
    },
    test("deletePermission delegates tenant and permission to repository") {
      val env = new Env()

      for
        _ <- env.repository.deletePermission.succeedsWith(())
        _ <- env.service.deletePermission(None, globalPermission.id)
      yield assertTrue(env.repository.deletePermission.calls === List((None, globalPermission.id)))
    },
    test("sync removes cached permission on delete event") {
      val env = new Env(Vector(globalPermission, tenantPermission))

      for
        _ <- env.service.sync(SyncEvent.PermissionsUpdated(Some(tenantId), tenantPermission.id, SyncEvent.Op.DELETE))
        cached <- env.cache.get
      yield assertTrue(cached === Vector(globalPermission))
    },
    test("sync upserts fetched permission for non-delete event") {
      val env = new Env(Vector(globalPermission, tenantPermission))
      val updatedPermission = tenantPermission.copy(
        description = Map("en" -> "Updated users"),
        endpointIds = Set(readManagedEndpointId),
      )

      for
        _ <- env.repository.findPermission.succeedsWith(Some(updatedPermission))
        _ <- env.service.sync(SyncEvent.PermissionsUpdated(Some(tenantId), tenantPermission.id, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findPermission.calls === List((Some(tenantId), tenantPermission.id)),
        cached === Vector(globalPermission, updatedPermission),
      )
    },
    test("sync removes cached permission when record is missing on non-delete event") {
      val env = new Env(Vector(globalPermission, tenantPermission))

      for
        _ <- env.repository.findPermission.succeedsWith(None)
        _ <- env.service.sync(SyncEvent.PermissionsUpdated(Some(tenantId), tenantPermission.id, SyncEvent.Op.UPDATE))
        cached <- env.cache.get
      yield assertTrue(
        env.repository.findPermission.calls === List((Some(tenantId), tenantPermission.id)),
        cached === Vector(globalPermission),
      )
    },
  )
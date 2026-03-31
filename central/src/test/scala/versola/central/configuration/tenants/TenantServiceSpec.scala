package versola.central.configuration.tenants

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.{CreateTenantRequest, UpdateTenantRequest}
import versola.util.ReloadingCache
import zio.*
import zio.test.*

object TenantServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenant1 = TenantId("tenant-a")
  private val tenant2 = TenantId("tenant-b")

  private val tenantRecord1 = TenantRecord(tenant1, "Tenant A", None)
  private val tenantRecord2 = TenantRecord(tenant2, "Tenant B", None)

  private val createRequest = CreateTenantRequest(tenant1, "Tenant A", None)
  private val updateRequest = UpdateTenantRequest(tenant1, "Updated Tenant A", None)

  class Env(initial: Vector[TenantRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[TenantRepository]
    val service = TenantService.Impl(cache, repository)

  def spec = suite("TenantService")(
    test("getAllTenants returns cached tenants sorted by id") {
      val env = new Env(Vector(tenantRecord2, tenantRecord1))

      for
        result <- env.service.getAllTenants
      yield assertTrue(result == Vector(tenantRecord1, tenantRecord2))
    },
    test("createTenant delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.createTenant.succeedsWith(())
        _ <- env.service.createTenant(createRequest)
      yield assertTrue(env.repository.createTenant.calls == List((tenant1, "Tenant A", None)))
    },
    test("updateTenant delegates request fields to repository") {
      val env = new Env()

      for
        _ <- env.repository.updateTenant.succeedsWith(())
        _ <- env.service.updateTenant(updateRequest)
      yield assertTrue(env.repository.updateTenant.calls == List((tenant1, "Updated Tenant A", None)))
    },
    test("deleteTenant delegates id to repository") {
      val env = new Env()

      for
        _ <- env.repository.deleteTenant.succeedsWith(())
        _ <- env.service.deleteTenant(tenant1)
      yield assertTrue(env.repository.deleteTenant.calls == List(tenant1))
    },
    test("sync reloads cache from repository") {
      val env = new Env()
      val refreshed = Vector(tenantRecord1, tenantRecord2)

      for
        _ <- env.repository.getAll.succeedsWith(refreshed)
        _ <- env.service.sync()
        cached <- env.cache.get
      yield assertTrue(cached == refreshed)
    },
  )
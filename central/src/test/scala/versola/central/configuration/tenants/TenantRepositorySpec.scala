package versola.central.configuration.tenants

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.test.*

trait TenantRepositorySpec extends DatabaseSpecBase[TenantRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenant1 = TenantId("tenant-a")
  val tenant2 = TenantId("tenant-b")

  override def testCases(env: TenantRepositorySpec.Env) =
    List(
      test("create, update, list and delete tenants") {
        for
          _ <- env.repository.createTenant(tenant2, "Tenant B", None)
          _ <- env.repository.createTenant(tenant1, "Tenant A", None)
          tenants <- env.repository.getAll
          _ <- env.repository.updateTenant(tenant1, "New description", None)
          updatedTenants <- env.repository.getAll
          _ <- env.repository.deleteTenant(tenant2)
          tenantsAfterDeletion <- env.repository.getAll
        yield assertTrue(
          tenants == Vector(
            TenantRecord(tenant1, "Tenant A", None),
            TenantRecord(tenant2, "Tenant B", None),
          ),
          updatedTenants == Vector(
            TenantRecord(tenant1, "New description", None),
            TenantRecord(tenant2, "Tenant B", None),
          ),
          tenantsAfterDeletion == Vector(
            TenantRecord(tenant1, "New description", None),
          ),
        )
      },
    )

object TenantRepositorySpec:
  case class Env(repository: TenantRepository)

package versola.central.configuration.tenants

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind, EdgeGroupRecord, EdgeGroupRepository, EdgeId, EdgeRecord, EdgeRepository}
import versola.util.DatabaseSpecBase
import zio.ZIO
import zio.test.*

trait TenantRepositorySpec extends DatabaseSpecBase[TenantRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenant1 = TenantId("tenant-a")
  val tenant2 = TenantId("tenant-b")
  val tenant3 = TenantId("tenant-c")
  val almaty = EdgeGroupId("tenant-spec-almaty")
  val astana = EdgeGroupId("tenant-spec-astana")

  private def edge(env: TenantRepositorySpec.Env, id: String, groupId: EdgeGroupId) =
    env.edges
      .createEdge(
        id = EdgeId(id),
        publicKeyJwk = None,
        requireDpopNonce = EdgeRecord.DefaultRequireDpopNonce,
        groupId = groupId,
        publicUrl = None,
      )
      .mapError:
        case failure: Throwable => failure
        case other => RuntimeException(other.toString)

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
      test("createTenant upserts: calling it again for an existing id overwrites the row instead of failing") {
        for
          _ <- env.repository.createTenant(tenant1, "Tenant A", None)
          _ <- env.repository.createTenant(tenant1, "Retried description", None)
          tenants <- env.repository.getAll
        yield assertTrue(
          tenants == Vector(TenantRecord(tenant1, "Retried description", None)),
        )
      },
      test("a tenant keeps the edge group it was created with until an update names another or none") {
        for
          _ <- env.edgeGroups.create(EdgeGroupRecord(almaty, EdgeGroupKind.FirstParty))
          _ <- env.edgeGroups.create(EdgeGroupRecord(astana, EdgeGroupKind.FirstParty))
          _ <- env.repository.createTenant(tenant1, "Tenant A", Some(almaty))
          created <- env.repository.getAll
          _ <- env.repository.updateTenant(tenant1, "Tenant A", Some(astana))
          moved <- env.repository.getAll
          _ <- env.repository.updateTenant(tenant1, "Tenant A", None)
          unserved <- env.repository.getAll
        yield assertTrue(
          created == Vector(TenantRecord(tenant1, "Tenant A", Some(almaty))),
          moved == Vector(TenantRecord(tenant1, "Tenant A", Some(astana))),
          unserved == Vector(TenantRecord(tenant1, "Tenant A", None)),
        )
      },
      // #557: several edge sites of one tenant. Every edge of the tenant's group serves it, an edge of another group
      // does not, and a tenant with no group is served by none.
      test("getServedBy lists the tenants of the group the edge belongs to, for every edge of that group") {
        for
          _ <- env.edgeGroups.create(EdgeGroupRecord(almaty, EdgeGroupKind.FirstParty))
          _ <- env.edgeGroups.create(EdgeGroupRecord(astana, EdgeGroupKind.FirstParty))
          _ <- edge(env, "tenant-spec-almaty-1", almaty)
          _ <- edge(env, "tenant-spec-almaty-2", almaty)
          _ <- edge(env, "tenant-spec-astana-1", astana)
          _ <- env.repository.createTenant(tenant2, "Tenant B", Some(almaty))
          _ <- env.repository.createTenant(tenant1, "Tenant A", Some(almaty))
          _ <- env.repository.createTenant(tenant3, "Tenant C", None)
          firstSite <- env.repository.getServedBy(EdgeId("tenant-spec-almaty-1"))
          secondSite <- env.repository.getServedBy(EdgeId("tenant-spec-almaty-2"))
          otherGroup <- env.repository.getServedBy(EdgeId("tenant-spec-astana-1"))
          unknown <- env.repository.getServedBy(EdgeId("tenant-spec-nowhere"))
        yield assertTrue(
          firstSite == Vector(tenant1, tenant2),
          secondSite == Vector(tenant1, tenant2),
          otherGroup.isEmpty,
          unknown.isEmpty,
        )
      },
    )

object TenantRepositorySpec:
  /** `edgeGroups` and `edges` write the groups and edges a case about who serves a tenant needs. */
  case class Env(repository: TenantRepository, edgeGroups: EdgeGroupRepository, edges: EdgeRepository)

package versola.configuration.tenants

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.tenants.TenantRepositorySpec
import versola.configuration.edges.{PostgresEdgeGroupRepository, PostgresEdgeRepository}
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresTenantRepositorySpec extends PostgresSpec, TenantRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield TenantRepositorySpec.Env(
        repository = PostgresTenantRepository(xa),
        edgeGroups = PostgresEdgeGroupRepository(xa),
        edges = PostgresEdgeRepository(xa),
      )

  override def beforeEach(env: TenantRepositorySpec.Env) =
    ZIO
      .serviceWithZIO[TransactorZIO](_.connect:
        sql"TRUNCATE TABLE tenants CASCADE".update.run()
        sql"DELETE FROM edges WHERE id LIKE 'tenant-spec-%'".update.run()
        sql"DELETE FROM edge_groups WHERE id LIKE 'tenant-spec-%'".update.run())
      .unit

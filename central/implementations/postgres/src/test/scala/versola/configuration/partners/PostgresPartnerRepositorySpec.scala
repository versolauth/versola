package versola.configuration.partners

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.edges.{EdgeGroupId, EdgeGroupKind}
import versola.central.configuration.partners.PartnerRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

object PostgresPartnerRepositorySpec extends PostgresSpec, PartnerRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PartnerRepositorySpec.Env(
        PostgresPartnerRepository(xa),
        new PartnerRepositorySpec.Fixtures:
          def group(id: EdgeGroupId, kind: EdgeGroupKind): Task[Unit] =
            xa.connect(sql"INSERT INTO edge_groups (id, kind) VALUES ($id, ${kind.code})".update.run()).unit,
      )

  override def beforeEach(env: PartnerRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE partners CASCADE".update.run()) *>
        xa.connect(sql"DELETE FROM edge_groups WHERE id <> 'default'".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A') ON CONFLICT (id) DO NOTHING".update.run())
    }.unit

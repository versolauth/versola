package versola.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.edges.{EdgeGroupId, EdgeIdentityRepositorySpec}
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresEdgeIdentityRepositorySpec extends PostgresSpec, EdgeIdentityRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield EdgeIdentityRepositorySpec.Env(
        PostgresEdgeRepository(xa),
        PostgresEdgeEnrollmentTokenRepository(xa),
        PostgresEdgeCertificateRepository(xa),
        PostgresEdgeGroupRepository(xa),
        new EdgeIdentityRepositorySpec.RawGroups:
          override def insert(id: EdgeGroupId, kind: String): Task[Unit] =
            xa.connect(sql"INSERT INTO edge_groups (id, kind) VALUES ($id, $kind)".update.run()).unit,
      )

  override def beforeEach(env: EdgeIdentityRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE edges RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"DELETE FROM edge_groups WHERE id <> 'default'".update.run())
    }.unit

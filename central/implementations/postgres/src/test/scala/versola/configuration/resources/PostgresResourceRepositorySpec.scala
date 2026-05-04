package versola.configuration.resources

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.resources.ResourceRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresResourceRepositorySpec extends PostgresSpec, ResourceRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield ResourceRepositorySpec.Env(resourceRepository = PostgresResourceRepository(xa))

  override def beforeEach(env: ResourceRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE resources, tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit
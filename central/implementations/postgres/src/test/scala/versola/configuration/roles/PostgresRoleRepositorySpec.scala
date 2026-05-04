package versola.configuration.roles

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.roles.RoleRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresRoleRepositorySpec extends PostgresSpec, RoleRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield RoleRepositorySpec.Env(PostgresRoleRepository(xa))

  override def beforeEach(env: RoleRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit
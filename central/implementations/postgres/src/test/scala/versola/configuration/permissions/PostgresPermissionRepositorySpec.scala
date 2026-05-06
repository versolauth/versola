package versola.configuration.permissions

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.permissions.PermissionRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresPermissionRepositorySpec extends PostgresSpec, PermissionRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PermissionRepositorySpec.Env(PostgresPermissionRepository(xa))

  override def beforeEach(env: PermissionRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit
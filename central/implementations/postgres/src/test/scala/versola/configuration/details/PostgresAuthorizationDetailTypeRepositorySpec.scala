package versola.configuration.details

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.details.AuthorizationDetailTypeRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresAuthorizationDetailTypeRepositorySpec extends PostgresSpec, AuthorizationDetailTypeRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield AuthorizationDetailTypeRepositorySpec.Env(PostgresAuthorizationDetailTypeRepository(xa))

  override def beforeEach(env: AuthorizationDetailTypeRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit

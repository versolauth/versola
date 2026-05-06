package versola.configuration.scopes

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.scopes.OAuthScopeRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresOAuthScopeRepositorySpec extends PostgresSpec, OAuthScopeRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield OAuthScopeRepositorySpec.Env(PostgresOAuthScopeRepository(xa))

  override def beforeEach(env: OAuthScopeRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit
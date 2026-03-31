package versola.configuration.clients

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.OAuthClientRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresOAuthClientRepositorySpec extends PostgresSpec, OAuthClientRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield OAuthClientRepositorySpec.Env(PostgresOAuthClientRepository(xa))

  override def beforeEach(env: OAuthClientRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit
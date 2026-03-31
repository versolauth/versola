package versola.configuration.tenants

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresTenantRepositorySpec extends PostgresSpec, TenantRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield TenantRepositorySpec.Env(PostgresTenantRepository(xa))

  override def beforeEach(env: TenantRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"TRUNCATE TABLE tenants CASCADE".update.run())).unit
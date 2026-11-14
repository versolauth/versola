package versola.configuration.edges

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.edges.EdgeIdentityRepositorySpec
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
      )

  override def beforeEach(env: EdgeIdentityRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE edges RESTART IDENTITY CASCADE".update.run())
    }.unit

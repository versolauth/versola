package versola.configuration.challenges

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.challenges.OtpChallengeRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresOtpChallengeRepositorySpec extends PostgresSpec, OtpChallengeRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield OtpChallengeRepositorySpec.Env(PostgresOtpChallengeRepository(xa))

  override def beforeEach(env: OtpChallengeRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A'), ('tenant-b', 'Tenant B')".update.run())
    }.unit

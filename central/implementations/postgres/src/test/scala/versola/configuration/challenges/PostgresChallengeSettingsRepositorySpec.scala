package versola.configuration.challenges

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.challenges.ChallengeSettingsRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresChallengeSettingsRepositorySpec extends PostgresSpec, ChallengeSettingsRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield ChallengeSettingsRepositorySpec.Env(PostgresChallengeSettingsRepository(xa))

  override def beforeEach(env: ChallengeSettingsRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(
          sql"""INSERT INTO tenants (id, description) VALUES
                ('tenant-a', 'Tenant A'),
                ('tenant-b', 'Tenant B')""".update.run()
        )
    }.unit

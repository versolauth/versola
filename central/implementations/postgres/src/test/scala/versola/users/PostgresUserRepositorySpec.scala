package versola.users

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.topology.CellId
import versola.central.users.UserRepositorySpec
import versola.util.SecureRandom
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresUserRepositorySpec extends PostgresSpec, UserRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        secureRandom <- SecureRandom.live.build.map(_.get[SecureRandom])
      yield UserRepositorySpec.Env(
        repository = PostgresUserRepository(xa, secureRandom),
        fixtures = new UserRepositorySpec.Fixtures:
          override def cell(id: CellId): Task[Unit] =
            val name = id: String
            xa.connect:
              sql"INSERT INTO partitions (id) VALUES ('kz') ON CONFLICT DO NOTHING".update.run()
              sql"INSERT INTO regions (id, partition_id) VALUES ('almaty', 'kz') ON CONFLICT DO NOTHING".update.run()
              sql"""INSERT INTO cells (id, region_id, internal_url, mtls_url, trusted_certificates, state, weight)
                    VALUES ($name, 'almaty', 'http://auth.internal:9003', NULL, '{}', 'open', 100)""".update.run()
            .unit,
      )

  override def beforeEach(env: UserRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE user_directory, identifier_directory, user_outbox, cells, regions, partitions CASCADE".update.run())
    }.unit

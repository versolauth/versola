package versola.user

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

import java.util.UUID

object PostgresUserRolesRepositorySpec extends PostgresSpec, UserRolesRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield UserRolesRepositorySpec.Env(PostgresUserRolesRepository(xa))

  override def beforeEach(env: UserRolesRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
        sql"TRUNCATE TABLE users CASCADE".update.run()
        sql"INSERT INTO users (id, claims) VALUES (${(userId1: UUID)}, '{}'::jsonb)".update.run()
    yield ()

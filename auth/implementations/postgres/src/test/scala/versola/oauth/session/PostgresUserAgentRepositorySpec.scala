package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

import java.util.UUID

object PostgresUserAgentRepositorySpec extends PostgresSpec, UserAgentRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield UserAgentRepositorySpec.Env(PostgresUserAgentRepository(xa))

  override def beforeEach(env: UserAgentRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
        sql"TRUNCATE TABLE users, user_agents CASCADE".update.run()
        sql"""INSERT INTO users (id, claims) VALUES (${(userId1: UUID)}, '{}'::jsonb), (${(userId2: UUID)}, '{}'::jsonb)""".update.run()
    yield ()

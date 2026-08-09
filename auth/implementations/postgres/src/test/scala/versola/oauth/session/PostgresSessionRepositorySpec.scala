package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.util.UUID

object PostgresSessionRepositorySpec extends PostgresSpec, SessionRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield SessionRepositorySpec.Env(PostgresSessionRepository(xa))

  override def beforeEach(env: SessionRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
        sql"TRUNCATE TABLE users, user_agents, sso_sessions, refresh_tokens CASCADE".update.run()
        sql"""INSERT INTO users (id, claims) VALUES (${(userId1: UUID)}, '{}'::jsonb), (${(userId2: UUID)}, '{}'::jsonb)""".update.run()
        sql"""
          INSERT INTO user_agents (id, user_id, platform, created_at, expires_at)
          VALUES
            (${(userAgentId1: UUID)}, ${(userId1: UUID)}, 'desktop', TIMESTAMP '2020-01-01 00:00:00+00', TIMESTAMP '2100-01-01 00:00:00+00'),
            (${(userAgentId2: UUID)}, ${(userId2: UUID)}, 'desktop', TIMESTAMP '2020-01-01 00:00:00+00', TIMESTAMP '2100-01-01 00:00:00+00')
        """.update.run()
    yield ()

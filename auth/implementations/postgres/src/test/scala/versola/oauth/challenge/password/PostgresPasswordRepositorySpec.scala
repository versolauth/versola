package versola.oauth.challenge.password

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.revocation.PostgresAuthRevocationRepository
import versola.oauth.session.PostgresSessionRepository
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

import java.util.UUID

object PostgresPasswordRepositorySpec extends PostgresSpec, PasswordRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PasswordRepositorySpec.Env(
        PostgresPasswordRepository(xa),
        PostgresSessionRepository(xa),
        PostgresAuthRevocationRepository(xa),
      )

  override def beforeEach(env: PasswordRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect:
        sql"TRUNCATE TABLE users, user_agents, sso_sessions, refresh_tokens, user_passwords, auth_revocations CASCADE".update.run()
        // The accounts and user agents the sessions of the cases belong to: what the case's own rows reference,
        // not what the repository under test writes.
        sql"""INSERT INTO users (id, claims) VALUES (${(userId1: UUID)}, '{}'::jsonb), (${(userId2: UUID)}, '{}'::jsonb)""".update.run()
        sql"""
          INSERT INTO user_agents (id, user_id, platform, created_at, expires_at)
          VALUES
            (${(userAgentId1: UUID)}, ${(userId1: UUID)}, 'desktop', TIMESTAMP '2020-01-01 00:00:00+00', TIMESTAMP '2100-01-01 00:00:00+00'),
            (${(userAgentId2: UUID)}, ${(userId2: UUID)}, 'desktop', TIMESTAMP '2020-01-01 00:00:00+00', TIMESTAMP '2100-01-01 00:00:00+00')
        """.update.run()
    }.unit

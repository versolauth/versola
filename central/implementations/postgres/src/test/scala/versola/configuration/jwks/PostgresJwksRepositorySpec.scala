package versola.configuration.jwks

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.jwks.JwksRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresJwksRepositorySpec extends PostgresSpec, JwksRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield JwksRepositorySpec.Env(PostgresJwksRepository(xa))

  // CASCADE: challenge_settings.signing_key_id now references jwks(kid), so a plain
  // TRUNCATE is refused once any row exists. This spec is not exercising that table, so
  // cascading through it is no different from truncating it directly.
  override def beforeEach(env: JwksRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE jwks CASCADE".update.run())
    }.unit

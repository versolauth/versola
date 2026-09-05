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

  override def beforeEach(env: JwksRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE jwks".update.run())
    }.unit

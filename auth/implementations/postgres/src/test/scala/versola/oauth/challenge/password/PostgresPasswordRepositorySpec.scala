package versola.oauth.challenge.password

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresPasswordRepositorySpec extends PostgresSpec, PasswordRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PasswordRepositorySpec.Env(PostgresPasswordRepository(xa))

  override def beforeEach(env: PasswordRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE user_passwords".update.run())
    }.unit

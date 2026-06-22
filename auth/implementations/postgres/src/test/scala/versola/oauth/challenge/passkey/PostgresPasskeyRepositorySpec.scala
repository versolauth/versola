package versola.oauth.challenge.passkey

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresPasskeyRepositorySpec extends PostgresSpec, PasskeyRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PasskeyRepositorySpec.Env(PostgresPasskeyRepository(xa))

  override def beforeEach(env: PasskeyRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE passkeys".update.run())
    }.unit

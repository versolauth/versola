package versola.oauth.challenge.passkey

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresPasskeyEnrollmentRepositorySpec extends PostgresSpec, PasskeyEnrollmentRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield PasskeyEnrollmentRepositorySpec.Env(PostgresPasskeyEnrollmentRepository(xa))

  override def beforeEach(env: PasskeyEnrollmentRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE passkey_enrollment".update.run())
    }.unit

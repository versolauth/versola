package versola.auth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresUserDeviceRepositorySpec extends PostgresSpec, UserDeviceRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield UserDeviceRepositorySpec.Env(PostgresUserDeviceRepository(xa))

  override def beforeEach(env: UserDeviceRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE user_devices".update.run())
    yield ()

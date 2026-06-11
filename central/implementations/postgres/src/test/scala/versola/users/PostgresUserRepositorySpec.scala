package versola.users

import com.augustnagro.magnum.sql
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.users.UserRepositorySpec
import versola.util.SecureRandom
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresUserRepositorySpec extends PostgresSpec, UserRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        secureRandom <- SecureRandom.live.build.map(_.get[SecureRandom])
      yield UserRepositorySpec.Env(PostgresUserRepository(xa, secureRandom))

  override def beforeEach(env: UserRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE user_index, user_outbox".update.run())
    }.unit

package versola.users

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.users.UserMoveRepositorySpec
import versola.util.SecureRandom
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresUserMoveRepositorySpec extends PostgresSpec, UserMoveRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
        secureRandom <- SecureRandom.live.build.map(_.get[SecureRandom])
      yield UserMoveRepositorySpec.Env(moves = PostgresUserMoveRepository(xa), users = PostgresUserRepository(xa, secureRandom))

  override def beforeEach(env: UserMoveRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(
        sql"TRUNCATE TABLE user_moves, user_directory, identifier_directory, user_outbox, user_outbox_dead".update.run(),
      )
    }.unit

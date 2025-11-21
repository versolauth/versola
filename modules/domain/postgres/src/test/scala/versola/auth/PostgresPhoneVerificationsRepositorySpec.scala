package versola.auth

object PostgresPhoneVerificationsRepositorySpec

/*
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresPhoneVerificationsRepositorySpec extends PostgresSpec, PhoneVerificationsRepositorySpec:

  override lazy val environment =
    ZLayer:
      for {
        xa <- ZIO.service[TransactorZIO]
      } yield PhoneVerificationsRepositorySpec.Env(
        PostgresPhoneVerificationsRepository(xa),
      )

  override def beforeEach(env: PhoneVerificationsRepositorySpec.Env) =
    for {
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE phone_verifications".update.run())
    } yield ()
*/

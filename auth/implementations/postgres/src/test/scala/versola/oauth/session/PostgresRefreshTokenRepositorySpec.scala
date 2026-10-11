package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.{DbCodec, sql}
import versola.util.MAC
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresRefreshTokenRepositorySpec extends PostgresSpec, RefreshTokenRepositorySpec:

  private given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield RefreshTokenRepositorySpec.Env(
        PostgresSessionRepository(xa),
        token => xa.connect(sql"SELECT xmin::text FROM refresh_tokens WHERE id = $token".query[String].run().headOption),
      )

  override def beforeEach(env: RefreshTokenRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE refresh_tokens".update.run())
    yield ()

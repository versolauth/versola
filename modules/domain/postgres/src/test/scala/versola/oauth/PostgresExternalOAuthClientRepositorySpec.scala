package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.model.OauthProviderName
import versola.util.postgres.PostgresSpec
import versola.util.{CryptoService, SecureRandom}
import zio.*

import javax.crypto.spec.SecretKeySpec

object PostgresExternalOAuthClientRepositorySpec extends PostgresSpec, ExternalOAuthClientRepositorySpec:

  private val testSecretKey =
    new SecretKeySpec(Array.fill(32)(0x42.toByte), "AES")

  override lazy val environment =
    SecureRandom.live >>> CryptoService.live >>> ZLayer {
      for {
        xa <- ZIO.service[TransactorZIO]
        cryptoService <- ZIO.service[CryptoService]
        repository = PostgresExternalOAuthClientRepository(xa, cryptoService, testSecretKey)
      } yield ExternalOAuthClientRepositorySpec.Env(repository)
    }

  override def beforeEach(env: ExternalOAuthClientRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE external_oauth_clients RESTART IDENTITY".update.run())
    yield ()

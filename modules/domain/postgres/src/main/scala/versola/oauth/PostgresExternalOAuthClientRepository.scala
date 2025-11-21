package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.model.{ClientId, ClientSecret, ExternalOAuthClient, OAuthProvider, OauthProviderName}
import versola.util.CryptoService
import versola.util.postgres.BasicCodecs
import zio.{Task, ZIO}

import javax.crypto.SecretKey

class PostgresExternalOAuthClientRepository(
    xa: TransactorZIO,
    cryptoService: CryptoService,
    secretKey: SecretKey,
) extends ExternalOAuthClientRepository, BasicCodecs:

  // Database record for external OAuth clients
  private case class ExternalOAuthClientRecord(
      id: Long,
      provider: String,
      clientId: String,
      password: Array[Byte],
      oldPassword: Option[Array[Byte]],
  )

  private given DbCodec[OauthProviderName] = DbCodec.StringCodec.biMap(OauthProviderName.valueOf, _.toString)
  private given DbCodec[ExternalOAuthClientRecord] = DbCodec.derived[ExternalOAuthClientRecord]

  override def register(provider: OauthProviderName, clientId: ClientId, clientSecret: ClientSecret): Task[Unit] =
    for
      encryptedPassword <- cryptoService.encryptAes256(clientSecret.getBytes(), secretKey)
      _ <- xa.connect:
        sql"""INSERT INTO external_oauth_clients (provider, client_id, password, old_password)
              VALUES ($provider, $clientId, $encryptedPassword, NULL)
           """.update.run()
    yield ()

  override def listAll(): Task[Vector[ExternalOAuthClient]] =
    for
      records <- xa.connect:
        sql"SELECT * FROM external_oauth_clients ORDER BY id"
          .query[ExternalOAuthClientRecord]
          .run()
      clients <- ZIO.foreach(records)(recordToClient)
    yield clients

  override def rotateSecret(provider: OauthProviderName, clientId: ClientId, newClientSecret: ClientSecret): Task[Unit] =
    for
      encryptedNewPassword <- cryptoService.encryptAes256(newClientSecret.getBytes(), secretKey)
      _ <- xa.connect:
        sql"""UPDATE external_oauth_clients
              SET old_password = password, password = $encryptedNewPassword
              WHERE client_id = $clientId AND provider = $provider
           """.update.run()
    yield ()

  override def deleteOldSecret(provider: OauthProviderName, clientId: ClientId): Task[Unit] =
    xa.connect:
      sql"""UPDATE external_oauth_clients
            SET old_password = NULL
            WHERE client_id = $clientId AND provider = $provider
         """.update.run()
    .unit

  private def recordToClient(record: ExternalOAuthClientRecord): Task[ExternalOAuthClient] =
    for
      decryptedPassword <- cryptoService.decryptAes256(record.password, secretKey)
      decryptedOldPassword <- record.oldPassword match
        case Some(oldPwd) => cryptoService.decryptAes256(oldPwd, secretKey).asSome
        case None => ZIO.none
    yield ExternalOAuthClient(
      id = record.id,
      provider = OauthProviderName.valueOf(record.provider),
      clientId = ClientId(record.clientId),
      clientSecret = ClientSecret(String(decryptedPassword)),
      oldClientSecret = decryptedOldPassword.map(bytes => ClientSecret(String(bytes))),
    )

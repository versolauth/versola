package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.model.*
import versola.util.postgres.BasicCodecs
import versola.util.{Argon2Hash, Argon2Salt}
import zio.*

class PostgresOAuthClientRepository(
    xa: TransactorZIO
) extends OAuthClientRepository, BasicCodecs:
  given DbCodec[OAuthClient] = DbCodec.derived[OAuthClient]

  override def create(client: OAuthClient): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO oauth_clients (id, client_name, redirect_uris, scope,
                                  secret_hash, secret_salt, previous_secret_hash, previous_secret_salt)
        VALUES (${client.id}, ${client.clientName}, ${client.redirectUris}, ${client.scope},
                ${client.secretHash}, ${client.secretSalt}, ${client.previousSecretHash}, ${client.previousSecretSalt})
      """.update.run()
    .unit

  override def update(clientId: ClientId, clientName: String, redirectUris: Set[String], scope: Set[String]): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE oauth_clients SET
          client_name = $clientName,
          redirect_uris = $redirectUris,
          scope = $scope
        WHERE id = $clientId
      """.update.run()
    .unit

  override def rotateSecret(clientId: ClientId, newHash: Argon2Hash, newSalt: Argon2Salt): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE oauth_clients
        SET previous_secret_hash = secret_hash,
            previous_secret_salt = secret_salt,
            secret_hash = $newHash,
            secret_salt = $newSalt
        WHERE id = $clientId
      """.update.run()
    .unit

  override def deletePreviousSecret(clientId: ClientId): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE oauth_clients
        SET previous_secret_hash = NULL,
            previous_secret_salt = NULL
        WHERE id = $clientId
      """.update.run()
    .unit

  override def delete(clientIds: Vector[ClientId]): Task[Unit] =
    xa.connect:
      batchUpdate(clientIds): clientId =>
        sql"""DELETE FROM oauth_clients WHERE id = $clientId""".update
    .unit

  override def getAll: Task[Map[ClientId, OAuthClient]] =
    xa.connect:
      sql"""
        SELECT id, client_name, redirect_uris, scope,
               secret_hash, secret_salt, previous_secret_hash, previous_secret_salt
        FROM oauth_clients
      """.query[OAuthClient].run()
        .map(client => client.id -> client).toMap

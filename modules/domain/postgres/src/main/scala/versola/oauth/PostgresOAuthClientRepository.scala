package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.model.*
import versola.util.postgres.BasicCodecs
import zio.*

class PostgresOAuthClientRepository(
    xa: TransactorZIO
) extends OAuthClientRepository, BasicCodecs:
  given DbCodec[OAuthClient] = DbCodec.derived[OAuthClient]

  override def create(client: OAuthClient): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO oauth_clients (id, client_name, redirect_uris, scope, secret, previous_secret)
        VALUES (${client.id}, ${client.clientName}, ${client.redirectUris}, ${client.scope},
                ${client.secret}, ${client.previousSecret})
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

  override def rotateSecret(clientId: ClientId, newSecret: Array[Byte]): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE oauth_clients
        SET previous_secret = secret,
            secret = $newSecret
        WHERE id = $clientId
      """.update.run()
    .unit

  override def deletePreviousSecret(clientId: ClientId): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE oauth_clients
        SET previous_secret = NULL
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
        SELECT id, client_name, redirect_uris, scope, secret, previous_secret
        FROM oauth_clients
      """.query[OAuthClient].run()
        .map(client => client.id -> client).toMap

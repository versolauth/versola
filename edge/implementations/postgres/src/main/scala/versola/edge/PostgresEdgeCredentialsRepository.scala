package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.*
import versola.edge.model.EdgeCredentials
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.util.Secret
import versola.util.postgres.BasicCodecs
import zio.Task

class PostgresEdgeCredentialsRepository(xa: TransactorZIO) extends EdgeCredentialsRepository, BasicCodecs:
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[Secret] = DbCodec.ByteArrayCodec.biMap(Secret(_), identity[Array[Byte]])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[EdgeCredentials] = DbCodec.derived[EdgeCredentials]

  override def find(clientId: ClientId): Task[Option[EdgeCredentials]] =
    xa.connect:
      sql"""
        SELECT client_id, client_secret_hash, provider_url, scopes, created_at
        FROM edge_credentials
        WHERE client_id = $clientId
      """.query[EdgeCredentials].run().headOption

  override def getAll: Task[Map[ClientId, EdgeCredentials]] =
    xa.connect:
      sql"""
        SELECT client_id, client_secret_hash, provider_url, scopes, created_at
        FROM edge_credentials
      """.query[EdgeCredentials].run()
    .map(_.map(cred => cred.clientId -> cred).toMap)

  override def create(credentials: EdgeCredentials): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO edge_credentials (client_id, client_secret_hash, provider_url, scopes, created_at)
        VALUES (
          ${credentials.clientId},
          ${credentials.clientSecretHash},
          ${credentials.providerUrl},
          ${credentials.scopes},
          ${credentials.createdAt}
        )
      """.update.run()
    .unit

  override def update(credentials: EdgeCredentials): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE edge_credentials
        SET client_secret_hash = ${credentials.clientSecretHash},
            provider_url = ${credentials.providerUrl},
            scopes = ${credentials.scopes}
        WHERE client_id = ${credentials.clientId}
      """.update.run()
    .unit

  override def delete(clientId: ClientId): Task[Unit] =
    xa.connect:
      sql"""
        DELETE FROM edge_credentials
        WHERE client_id = $clientId
      """.update.run()
    .unit


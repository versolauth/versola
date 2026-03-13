package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec
import versola.oauth.client.OAuthClientRepository
import versola.oauth.client.model.{ClientId, OAuthClientRecord}
import versola.oauth.model.*
import versola.util.Secret
import versola.util.postgres.BasicCodecs
import zio.*

class PostgresOAuthClientRepository(
    xa: TransactorZIO,
) extends OAuthClientRepository, BasicCodecs:
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[Secret] = DbCodec.ByteArrayCodec.biMap(Secret(_), identity[Array[Byte]])
  given DbCodec[Duration] = DbCodec.LongCodec.biMap(Duration.fromSeconds, _.toSeconds)
  given DbCodec[List[ClientId]] = PgCodec.SeqCodec[String].biMap(_.map(ClientId(_)).toList, _.map(identity[String]))
  given DbCodec[OAuthClientRecord] = DbCodec.derived[OAuthClientRecord]

  override def create(client: OAuthClientRecord): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO oauth_clients (id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl)
        VALUES (${client.id}, ${client.clientName}, ${client.redirectUris}, ${client.scope},
                ${client.externalAudience}, ${client.secret}, ${client.previousSecret}, ${client.accessTokenTtl})
      """.update.run()
    .unit

  override def update(
      clientId: ClientId,
      clientName: String,
      redirectUris: Set[String],
      scope: Set[String],
  ): Task[Unit] =
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

  override def getAll: Task[Map[ClientId, OAuthClientRecord]] =
    xa.connect:
      sql"""
        SELECT id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl
        FROM oauth_clients
      """.query[OAuthClientRecord].run()
        .map(client => client.id -> client).toMap

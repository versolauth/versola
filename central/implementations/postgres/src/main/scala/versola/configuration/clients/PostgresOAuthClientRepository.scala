package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.{ClientId, OAuthClientRecord, OAuthClientRepository}
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchClientRedirectUris, PatchClientScope, PatchPermissions}
import versola.util.{RedirectUri, Secret}
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZLayer}

class PostgresOAuthClientRepository(
    xa: TransactorZIO,
) extends OAuthClientRepository, BasicCodecs:

  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[Permission] = DbCodec.StringCodec.biMap(Permission(_), identity[String])
  given DbCodec[RedirectUri] = DbCodec.StringCodec.biMap(RedirectUri(_), identity[String])
  given DbCodec[Secret] = DbCodec.ByteArrayCodec.biMap(Secret(_), identity[Array[Byte]])
  given DbCodec[Duration] = DbCodec.LongCodec.biMap(Duration.fromSeconds, _.toSeconds)
  given DbCodec[OAuthClientRecord] = DbCodec.derived

  private def findClient(tenantId: TenantId, clientId: ClientId) =
    sql"""
      SELECT id, tenant_id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl, permissions
      FROM oauth_clients
      WHERE tenant_id = $tenantId AND id = $clientId
    """

  override def getAll: Task[Vector[OAuthClientRecord]] =
    xa.connect:
      sql"""
        SELECT id, tenant_id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl, permissions
        FROM oauth_clients
      """
        .query[OAuthClientRecord].run()

  override def find(tenantId: TenantId, clientId: ClientId): Task[Option[OAuthClientRecord]] =
    xa.connect:
      findClient(tenantId, clientId).query[OAuthClientRecord].run().headOption

  override def createClient(client: OAuthClientRecord): Task[Unit] =
    xa.connect:
      sql"""
        INSERT INTO oauth_clients (id, tenant_id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl, permissions)
        VALUES (${client.id}, ${client.tenantId}, ${client.clientName}, ${client.redirectUris}, ${client.scope},
                ${client.externalAudience}, ${client.secret}, ${client.previousSecret}, ${client.accessTokenTtl}, ${client.permissions})
      """.update.run()
    .unit

  override def updateClient(
      tenantId: TenantId,
      clientId: ClientId,
      clientName: Option[String],
      patchRedirectUris: PatchClientRedirectUris,
      patchScope: PatchClientScope,
      patchPermissions: PatchPermissions,
      accessTokenTtl: Option[Duration]
  ): Task[Unit] =
    xa.repeatableRead.transact:
      val client = findClient(tenantId, clientId).query[OAuthClientRecord].run().head
      val newClientName = clientName.getOrElse(client.clientName)
      val newRedirectUris = client.redirectUris -- patchRedirectUris.remove ++ patchRedirectUris.add
      val newScope = client.scope -- patchScope.remove ++ patchScope.add
      val newPermissions = client.permissions -- patchPermissions.remove ++ patchPermissions.add
      val newAccessTokenTtl = accessTokenTtl.getOrElse(client.accessTokenTtl)
      sql"""
        UPDATE oauth_clients SET
          client_name = $newClientName,
          redirect_uris = $newRedirectUris,
          scope = $newScope,
          permissions = $newPermissions,
          access_token_ttl = $newAccessTokenTtl
        WHERE tenant_id = $tenantId AND id = $clientId
      """.update.run()
    .unit

  override def rotateClientSecret(tenantId: TenantId, clientId: ClientId, newSecret: Array[Byte]): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE oauth_clients
        SET previous_secret = secret,
            secret = $newSecret
        WHERE tenant_id = $tenantId AND id = $clientId
      """.update.run()
    .unit

  override def deletePreviousClientSecret(tenantId: TenantId, clientId: ClientId): Task[Unit] =
    xa.connect:
      sql"""
        UPDATE oauth_clients
        SET previous_secret = NULL
        WHERE tenant_id = $tenantId AND id = $clientId
      """.update.run()
    .unit

  override def deleteClient(tenantId: TenantId, clientId: ClientId): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM oauth_clients WHERE tenant_id = $tenantId AND id = $clientId""".update.run()
    .unit

object PostgresOAuthClientRepository:
  def live: ZLayer[TransactorZIO, Throwable, OAuthClientRepository] =
    ZLayer.fromFunction(PostgresOAuthClientRepository(_))

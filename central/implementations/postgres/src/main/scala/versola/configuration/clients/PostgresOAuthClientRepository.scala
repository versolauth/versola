package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.{AuthFlow, ClientAlreadyExists, ClientId, OAuthClientRecord, OAuthClientRepository}
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchClientRedirectUris, PatchClientScope, PatchPermissions}
import versola.util.{RedirectUri, Secret}
import versola.util.postgres.BasicCodecs
import zio.{Duration, IO, Task, ZIO, ZLayer}

import java.sql.SQLException

class PostgresOAuthClientRepository(
    xa: TransactorZIO,
) extends OAuthClientRepository, BasicCodecs:

  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[Permission] = DbCodec.StringCodec.biMap(Permission(_), identity[String])
  given DbCodec[RedirectUri] = DbCodec.StringCodec.biMap(RedirectUri(_), identity[String])
  given DbCodec[Duration] = DbCodec.LongCodec.biMap(Duration.fromSeconds, _.toSeconds)
  given DbCodec[AuthFlow] = jsonBCodec[AuthFlow]
  given DbCodec[OAuthClientRecord] = DbCodec.derived

  private def findClient(clientId: ClientId) =
    sql"""
      SELECT id, tenant_id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id
      FROM oauth_clients
      WHERE id = $clientId
    """

  override def getAll: Task[Vector[OAuthClientRecord]] =
    xa.connectMeasured("get-all-clients"):
      sql"""
        SELECT id, tenant_id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id
        FROM oauth_clients
      """
        .query[OAuthClientRecord].run()

  override def find(clientId: ClientId): Task[Option[OAuthClientRecord]] =
    xa.connectMeasured("find-client"):
      findClient(clientId).query[OAuthClientRecord].run().headOption

  override def createClient(client: OAuthClientRecord): IO[ClientAlreadyExists | Throwable, Unit] =
    xa.connectMeasured("create-client"):
      sql"""
        INSERT INTO oauth_clients (id, tenant_id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id)
        VALUES (${client.id}, ${client.tenantId}, ${client.clientName}, ${client.redirectUris}, ${client.scope},
                ${client.externalAudience}, ${client.secret}, ${client.previousSecret}, ${client.accessTokenTtl}, ${client.refreshTokenTtl}, ${client.permissions}, ${client.theme}, ${client.authFlow}, ${client.otpTemplateId})
      """.update.run()
    .unit
    .mapError {
      case e if PostgresOAuthClientRepository.isUniqueViolation(e) => ClientAlreadyExists(client.id)
      case e: Throwable                                            => e
    }

  override def updateClient(
      clientId: ClientId,
      clientName: Option[String],
      patchRedirectUris: PatchClientRedirectUris,
      patchScope: PatchClientScope,
      patchPermissions: PatchPermissions,
      accessTokenTtl: Option[Duration],
      refreshTokenTtl: Option[Duration],
      theme: Option[String],
      authFlow: Option[AuthFlow],
      otpTemplateId: Option[String],
  ): Task[Unit] =
    xa.transactMeasured("update-client"):
      // Lock the row (READ_COMMITTED + FOR UPDATE) to prevent lost updates from concurrent writers.
      val client = sql"""
        SELECT id, tenant_id, client_name, redirect_uris, scope, external_audience, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id
        FROM oauth_clients
        WHERE id = $clientId
        FOR UPDATE
      """.query[OAuthClientRecord].run().head
      val newClientName = clientName.getOrElse(client.clientName)
      val newRedirectUris = client.redirectUris -- patchRedirectUris.remove ++ patchRedirectUris.add
      val newScope = client.scope -- patchScope.remove ++ patchScope.add
      val newPermissions = client.permissions -- patchPermissions.remove ++ patchPermissions.add
      val newAccessTokenTtl = accessTokenTtl.getOrElse(client.accessTokenTtl)
      val newRefreshTokenTtl = refreshTokenTtl.getOrElse(client.refreshTokenTtl)
      val newTheme = theme.getOrElse(client.theme)
      val newAuthFlow = authFlow.orElse(client.authFlow)
      val newOtpTemplateId = otpTemplateId.getOrElse(client.otpTemplateId)
      sql"""
        UPDATE oauth_clients SET
          client_name = $newClientName,
          redirect_uris = $newRedirectUris,
          scope = $newScope,
          permissions = $newPermissions,
          access_token_ttl = $newAccessTokenTtl,
          refresh_token_ttl = $newRefreshTokenTtl,
          theme = $newTheme,
          auth_flow = $newAuthFlow,
          otp_template_id = $newOtpTemplateId
        WHERE id = $clientId
      """.update.run()
    .unit

  override def rotateClientSecret(clientId: ClientId, newSecret: Array[Byte]): Task[Unit] =
    xa.connectMeasured("rotate-client-secret"):
      sql"""
        UPDATE oauth_clients
        SET previous_secret = secret,
            secret = $newSecret
        WHERE id = $clientId
      """.update.run()
    .unit

  override def deletePreviousClientSecret(clientId: ClientId): Task[Unit] =
    xa.connectMeasured("delete-previous-client-secret"):
      sql"""
        UPDATE oauth_clients
        SET previous_secret = NULL
        WHERE id = $clientId
      """.update.run()
    .unit

  override def deleteClient(clientId: ClientId): Task[Unit] =
    xa.connectMeasured("delete-client"):
      sql"""DELETE FROM oauth_clients WHERE id = $clientId""".update.run()
    .unit

object PostgresOAuthClientRepository:
  def live: ZLayer[TransactorZIO, Throwable, OAuthClientRepository] =
    ZLayer.fromFunction(PostgresOAuthClientRepository(_))

  private val UniqueViolationSqlState = "23505"

  private def isUniqueViolation(t: Throwable): Boolean = t match
    case sql: SQLException => sql.getSQLState == UniqueViolationSqlState
    case _                 => Option(t.getCause).exists(isUniqueViolation)

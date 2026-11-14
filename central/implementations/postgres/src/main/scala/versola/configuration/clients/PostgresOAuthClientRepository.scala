package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.SqlArrayCodec
import versola.central.configuration.clients.{
  ApplicationType,
  AuthFlow,
  AuthMethod,
  ClientAlreadyExists,
  ClientApproval,
  ClientId,
  ClientTemplate,
  ConsentFlow,
  MutualTlsAuth,
  OAuthClientPatch,
  OAuthClientRecord,
  OAuthClientRepository,
}
import versola.central.configuration.partners.{PartnerClientLimitReached, PartnerId, PartnerLimits}
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchClientRedirectUris, PatchClientScope, PatchPermissions}
import versola.util.postgres.BasicCodecs
import versola.util.theme.ThemeOverrides
import versola.util.{Dpop, JsonWebKeySet, Patch, RedirectUri, Secret}
import zio.http.URL
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.{Duration, IO, Task, ZIO, ZLayer}

import java.sql.SQLException
import java.time.Instant

class PostgresOAuthClientRepository(
    xa: TransactorZIO,
) extends OAuthClientRepository, BasicCodecs:

  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[Permission] = DbCodec.StringCodec.biMap(Permission(_), identity[String])
  given DbCodec[RedirectUri] = DbCodec.StringCodec.biMap(RedirectUri(_), identity[String])
  given DbCodec[Duration] = DbCodec.LongCodec.biMap(Duration.fromSeconds, _.toSeconds)
  given DbCodec[URL] = DbCodec.StringCodec.biMap(URL.decode(_).fold(throw _, identity), _.encode)
  given DbCodec[AuthFlow] = jsonBCodec[AuthFlow]
  given DbCodec[ConsentFlow] = jsonBCodec[ConsentFlow]
  given DbCodec[MutualTlsAuth] = jsonBCodec[MutualTlsAuth]
  given DbCodec[Dpop.Algorithm] = DbCodec.StringCodec.biMap(decodeDpopAlgorithm, _.toString)
  given SqlArrayCodec[Dpop.Algorithm] = new SqlArrayCodec[Dpop.Algorithm]:
    override val jdbcTypeName: String = "text"
    override def readArray(array: Object): Array[Dpop.Algorithm] =
      array.asInstanceOf[Array[String]].map(decodeDpopAlgorithm)
    override def toArrayObj(entity: Dpop.Algorithm): Object = entity.toString
  given DbCodec[JsonWebKeySet] = jsonBCodec[JsonWebKeySet]
  given DbCodec[AuthMethod] = DbCodec.StringCodec.biMap(decodeAuthMethod, _.toString)
  given DbCodec[ClientTemplate] = jsonBCodec[ClientTemplate]
  given DbCodec[ApplicationType] = DbCodec.StringCodec.biMap(decodeApplicationType, _.toString)
  given DbCodec[ThemeOverrides] = jsonBCodec[ThemeOverrides]
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[PartnerId] = DbCodec.StringCodec.biMap(PartnerId(_), identity[String])
  given DbCodec[PartnerLimits] = jsonBCodec[PartnerLimits]
  given DbCodec[Json.Arr] = jsonBCodec[Json.Arr]
  given DbCodec[ClientApproval] = DbCodec.StringCodec.biMap(decodeApproval, _.code)
  given DbCodec[OAuthClientRecord] = DbCodec.derived

  /** A name the enum no longer has is a corrupt row, not a client to serve with a policy it
    * did not register -- registration only ever writes values this parses. */
  private def decodeDpopAlgorithm(name: String): Dpop.Algorithm =
    Dpop.Algorithm.fromName(name)
      .getOrElse(throw IllegalStateException(s"unknown DPoP signing algorithm: $name"))

  /** Same reasoning as [[decodeDpopAlgorithm]], and rather more pointed: a method this
    * cannot read is a client whose credential is unknown, and guessing one would be guessing
    * what authenticates it. */
  private def decodeAuthMethod(name: String): AuthMethod =
    AuthMethod.values.find(_.toString == name)
      .getOrElse(throw IllegalStateException(s"unknown client auth method: $name"))

  private def decodeApplicationType(name: String): ApplicationType =
    ApplicationType.values.find(_.toString == name)
      .getOrElse(throw IllegalStateException(s"unknown client application type: $name"))

  private def decodeApproval(code: String): ClientApproval =
    ClientApproval.from(code).getOrElse(throw IllegalStateException(s"unknown client approval: $code"))

  private def findClient(clientId: ClientId) =
    sql"""
      SELECT id, tenant_id, client_name, redirect_uris, scope, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id, front_channel_logout_uri, front_channel_logout_session_required, back_channel_logout_uri, logo_uri, policy_uri, tos_uri, consent_flow, dpop_bound_access_tokens, dpop_signing_algs, dpop_min_rsa_key_size, auth_method, mtls_auth, certificate_bound_access_tokens, jwks, require_signed_request_object, require_pushed_authorization_requests, edge_signing_key, edge_client_certificate, template, created_at, application_type, theme_overrides, partner_id, approval
      FROM oauth_clients
      WHERE id = $clientId
    """

  override def getAll: Task[Vector[OAuthClientRecord]] =
    xa.connectMeasured("get-all-clients"):
      sql"""
        SELECT id, tenant_id, client_name, redirect_uris, scope, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id, front_channel_logout_uri, front_channel_logout_session_required, back_channel_logout_uri, logo_uri, policy_uri, tos_uri, consent_flow, dpop_bound_access_tokens, dpop_signing_algs, dpop_min_rsa_key_size, auth_method, mtls_auth, certificate_bound_access_tokens, jwks, require_signed_request_object, require_pushed_authorization_requests, edge_signing_key, edge_client_certificate, template, created_at, application_type, theme_overrides, partner_id, approval
        FROM oauth_clients
      """
        .query[OAuthClientRecord].run()

  override def find(clientId: ClientId): Task[Option[OAuthClientRecord]] =
    xa.connectMeasured("find-client"):
      findClient(clientId).query[OAuthClientRecord].run().headOption

  override def createClient(client: OAuthClientRecord): IO[ClientAlreadyExists | PartnerClientLimitReached | Throwable, Unit] =
    xa.transactMeasured("create-client"):
      // A partner's client is counted against the partner's limit under the partner's row lock (`FOR NO KEY UPDATE`, which the foreign
      // keys that point at the row do not wait on), so two creations of one partner run one after the other and the second counts
      // the first. The decision is made here, not in the database: the lock and the count are all SQL does.
      client.partnerId.foreach: partnerId =>
        val limits = sql"SELECT limits FROM partners WHERE id = $partnerId FOR NO KEY UPDATE".query[PartnerLimits].run().headOption
        limits.foreach: limits =>
          val console = sql"SELECT console_client_id FROM partners WHERE id = $partnerId".query[ClientId].run().head
          if client.id != console then
            val existing =
              sql"SELECT COUNT(*) FROM oauth_clients WHERE partner_id = $partnerId AND id <> $console".query[Long].run().head
            if existing >= limits.maxClients then throw PartnerClientLimitReached(partnerId, limits.maxClients)
      sql"""
        INSERT INTO oauth_clients (id, tenant_id, client_name, redirect_uris, scope, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id, front_channel_logout_uri, front_channel_logout_session_required, back_channel_logout_uri, logo_uri, policy_uri, tos_uri, consent_flow, dpop_bound_access_tokens, dpop_signing_algs, dpop_min_rsa_key_size, auth_method, mtls_auth, certificate_bound_access_tokens, jwks, require_signed_request_object, require_pushed_authorization_requests, edge_signing_key, edge_client_certificate, template, created_at, application_type, theme_overrides, partner_id, approval)
        VALUES (${client.id}, ${client.tenantId}, ${client.clientName}, ${client.redirectUris}, ${client.scope},
                ${client.secret}, ${client.previousSecret}, ${client.accessTokenTtl}, ${client.refreshTokenTtl}, ${client.permissions}, ${client.theme}, ${client.authFlow}, ${client.otpTemplateId}, ${client.frontChannelLogoutUri}, ${client.frontChannelLogoutSessionRequired}, ${client.backChannelLogoutUri}, ${client.logoUri}, ${client.policyUri}, ${client.tosUri}, ${client.consentFlow}, ${client.dpopBoundAccessTokens}, ${client.dpopSigningAlgs}, ${client.dpopMinRsaKeySize}, ${client.authMethod}, ${client.mtlsAuth}, ${client.certificateBoundAccessTokens}, ${client.jwks}, ${client.requireSignedRequestObject}, ${client.requirePushedAuthorizationRequests}, ${client.edgeSigningKey}, ${client.edgeClientCertificate}, ${client.template}, ${client.createdAt}, ${client.applicationType}, ${client.themeOverrides}, ${client.partnerId}, ${client.approval})
      """.update.run()
    .unit
      .mapError {
        case e: PartnerClientLimitReached => e
        case e if PostgresOAuthClientRepository.isUniqueViolation(e) => ClientAlreadyExists(client.id)
        case e: Throwable => e
      }

  override def updateClient(clientId: ClientId, patch: OAuthClientPatch): Task[Unit] =
    xa.transactMeasured("update-client"):
      // Lock the row (READ_COMMITTED + FOR UPDATE) to prevent lost updates from concurrent writers.
      val client =
        sql"""
        SELECT id, tenant_id, client_name, redirect_uris, scope, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, otp_template_id, front_channel_logout_uri, front_channel_logout_session_required, back_channel_logout_uri, logo_uri, policy_uri, tos_uri, consent_flow, dpop_bound_access_tokens, dpop_signing_algs, dpop_min_rsa_key_size, auth_method, mtls_auth, certificate_bound_access_tokens, jwks, require_signed_request_object, require_pushed_authorization_requests, edge_signing_key, edge_client_certificate, template, created_at, application_type, theme_overrides, partner_id, approval
        FROM oauth_clients
        WHERE id = $clientId
        FOR UPDATE
      """.query[OAuthClientRecord].run().head
      val newClientName = patch.clientName.getOrElse(client.clientName)
      val newRedirectUris = client.redirectUris -- patch.redirectUris.remove ++ patch.redirectUris.add
      val newScope = client.scope -- patch.scope.remove ++ patch.scope.add
      val newPermissions = client.permissions -- patch.permissions.remove ++ patch.permissions.add
      val newAccessTokenTtl = patch.accessTokenTtl.getOrElse(client.accessTokenTtl)
      val newRefreshTokenTtl = patch.refreshTokenTtl.getOrElse(client.refreshTokenTtl)
      val newTheme = patch.theme.getOrElse(client.theme)
      val newAuthFlow = patch.authFlow.applyTo(client.authFlow)
      val newOtpTemplateId = patch.otpTemplateId.getOrElse(client.otpTemplateId)
      val newFrontChannelLogoutUri = patch.frontChannelLogoutUri.applyTo(client.frontChannelLogoutUri)
      val newFrontChannelLogoutSessionRequired = patch.frontChannelLogoutSessionRequired.getOrElse(client.frontChannelLogoutSessionRequired)
      val newBackChannelLogoutUri = patch.backChannelLogoutUri.applyTo(client.backChannelLogoutUri)
      val newLogoUri = patch.logoUri.applyTo(client.logoUri)
      val newPolicyUri = patch.policyUri.applyTo(client.policyUri)
      val newTosUri = patch.tosUri.applyTo(client.tosUri)
      val newConsentFlow = patch.consentFlow.applyTo(client.consentFlow)
      val newDpopBoundAccessTokens = patch.dpopBoundAccessTokens.getOrElse(client.dpopBoundAccessTokens)
      val newDpopSigningAlgs = patch.dpopSigningAlgs.getOrElse(client.dpopSigningAlgs)
      val newDpopMinRsaKeySize = patch.dpopMinRsaKeySize.applyTo(client.dpopMinRsaKeySize)
      val newMtlsAuth = patch.mtlsAuth.applyTo(client.mtlsAuth)
      val newCertificateBound = patch.certificateBoundAccessTokens.getOrElse(client.certificateBoundAccessTokens)
      val newJwks = patch.jwks.applyTo(client.jwks)
      val newRequireSignedRequestObject = patch.requireSignedRequestObject.getOrElse(client.requireSignedRequestObject)
      val newRequirePushedAuthorizationRequests =
        patch.requirePushedAuthorizationRequests.getOrElse(client.requirePushedAuthorizationRequests)
      val newEdgeSigningKey = patch.edgeSigningKey.applyTo(client.edgeSigningKey)
      val newEdgeClientCertificate = patch.edgeClientCertificate.applyTo(client.edgeClientCertificate)
      val newApplicationType = patch.applicationType.getOrElse(client.applicationType)
      // An empty value clears, so what is stored is either something to apply or nothing.
      val newThemeOverrides = patch.themeOverrides.fold(client.themeOverrides)(overrides => Option.unless(overrides.isEmpty)(overrides))
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
          otp_template_id = $newOtpTemplateId,
          front_channel_logout_uri = $newFrontChannelLogoutUri,
          front_channel_logout_session_required = $newFrontChannelLogoutSessionRequired,
          back_channel_logout_uri = $newBackChannelLogoutUri,
          logo_uri = $newLogoUri,
          policy_uri = $newPolicyUri,
          tos_uri = $newTosUri,
          consent_flow = $newConsentFlow,
          dpop_bound_access_tokens = $newDpopBoundAccessTokens,
          dpop_signing_algs = $newDpopSigningAlgs,
          dpop_min_rsa_key_size = $newDpopMinRsaKeySize,
          mtls_auth = $newMtlsAuth,
          certificate_bound_access_tokens = $newCertificateBound,
          jwks = $newJwks,
          require_signed_request_object = $newRequireSignedRequestObject,
          require_pushed_authorization_requests = $newRequirePushedAuthorizationRequests,
          edge_signing_key = $newEdgeSigningKey,
          edge_client_certificate = $newEdgeClientCertificate,
          application_type = $newApplicationType,
          theme_overrides = $newThemeOverrides
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

  override def setApproval(clientId: ClientId, approval: ClientApproval): Task[Boolean] =
    xa.connectMeasured("set-client-approval"):
      sql"""UPDATE oauth_clients SET approval = $approval WHERE id = $clientId""".update.run() > 0

  override def deleteClient(clientId: ClientId): Task[Unit] =
    xa.transactMeasured("delete-client"):
      // A client id is global: one registered later under the same id (first-party or another partner's) would be in the audience of a
      // resource the deleted client was in, and have the limits that were set for it. The id leaves them in the same transaction.
      val resources = sql"SELECT resource_id FROM resources WHERE $clientId = ANY(audience)".query[String].run()
      resources.foreach: resourceId =>
        val limits = sql"SELECT audience_rate_limits FROM resources WHERE resource_id = $resourceId".query[Json.Arr].run().head
        val kept = Json.Arr(limits.elements.filterNot(entry => entry.asObject.flatMap(_.get("clientId")).contains(Json.Str(clientId: String))))
        sql"""
          UPDATE resources SET audience = array_remove(audience, $clientId), audience_rate_limits = ${kept.toJson}::jsonb
          WHERE resource_id = $resourceId
        """.update.run()
      sql"""DELETE FROM oauth_clients WHERE id = $clientId""".update.run()
    .unit

object PostgresOAuthClientRepository:
  def live: ZLayer[TransactorZIO, Throwable, OAuthClientRepository] =
    ZLayer.fromFunction(PostgresOAuthClientRepository(_))

  private val UniqueViolationSqlState = "23505"

  private def isUniqueViolation(t: Throwable): Boolean = t match
    case sql: SQLException => sql.getSQLState == UniqueViolationSqlState
    case _ => Option(t.getCause).exists(isUniqueViolation)

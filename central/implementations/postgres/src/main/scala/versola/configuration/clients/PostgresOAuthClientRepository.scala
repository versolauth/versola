package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.SqlArrayCodec
import versola.central.configuration.clients.{AuthFlow, ClientAlreadyExists, ClientId, ConsentFlow, MutualTlsAuth, OAuthClientPatch, OAuthClientRecord, OAuthClientRepository, RegistrationFlow}
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchClientRedirectUris, PatchClientScope, PatchPermissions}
import versola.util.{Dpop, JsonWebKeySet, Patch, RedirectUri, Secret}
import versola.util.postgres.BasicCodecs
import zio.http.URL
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
  given DbCodec[URL] = DbCodec.StringCodec.biMap(URL.decode(_).fold(throw _, identity), _.encode)
  given DbCodec[AuthFlow] = jsonBCodec[AuthFlow]
  given DbCodec[RegistrationFlow] = jsonBCodec[RegistrationFlow]
  given DbCodec[ConsentFlow] = jsonBCodec[ConsentFlow]
  given DbCodec[MutualTlsAuth] = jsonBCodec[MutualTlsAuth]
  given DbCodec[Dpop.Algorithm] = DbCodec.StringCodec.biMap(decodeDpopAlgorithm, _.toString)
  given SqlArrayCodec[Dpop.Algorithm] = new SqlArrayCodec[Dpop.Algorithm]:
    override val jdbcTypeName: String = "text"
    override def readArray(array: Object): Array[Dpop.Algorithm] =
      array.asInstanceOf[Array[String]].map(decodeDpopAlgorithm)
    override def toArrayObj(entity: Dpop.Algorithm): Object = entity.toString
  given DbCodec[JsonWebKeySet] = jsonBCodec[JsonWebKeySet]
  given DbCodec[OAuthClientRecord] = DbCodec.derived

  /** A name the enum no longer has is a corrupt row, not a client to serve with a policy it
    * did not register -- registration only ever writes values this parses. */
  private def decodeDpopAlgorithm(name: String): Dpop.Algorithm =
    Dpop.Algorithm.fromName(name)
      .getOrElse(throw IllegalStateException(s"unknown DPoP signing algorithm: $name"))

  private def findClient(clientId: ClientId) =
    sql"""
      SELECT id, tenant_id, client_name, redirect_uris, scope, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, registration_flow, otp_template_id, front_channel_logout_uri, front_channel_logout_session_required, back_channel_logout_uri, logo_uri, policy_uri, tos_uri, consent_flow, dpop_bound_access_tokens, dpop_signing_algs, dpop_min_rsa_key_size, mtls_auth, certificate_bound_access_tokens, jwks, require_signed_request_object, require_pushed_authorization_requests, edge_signing_key
      FROM oauth_clients
      WHERE id = $clientId
    """

  override def getAll: Task[Vector[OAuthClientRecord]] =
    xa.connectMeasured("get-all-clients"):
      sql"""
        SELECT id, tenant_id, client_name, redirect_uris, scope, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, registration_flow, otp_template_id, front_channel_logout_uri, front_channel_logout_session_required, back_channel_logout_uri, logo_uri, policy_uri, tos_uri, consent_flow, dpop_bound_access_tokens, dpop_signing_algs, dpop_min_rsa_key_size, mtls_auth, certificate_bound_access_tokens, jwks, require_signed_request_object, require_pushed_authorization_requests, edge_signing_key
        FROM oauth_clients
      """
        .query[OAuthClientRecord].run()

  override def find(clientId: ClientId): Task[Option[OAuthClientRecord]] =
    xa.connectMeasured("find-client"):
      findClient(clientId).query[OAuthClientRecord].run().headOption

  override def createClient(client: OAuthClientRecord): IO[ClientAlreadyExists | Throwable, Unit] =
    xa.connectMeasured("create-client"):
      sql"""
        INSERT INTO oauth_clients (id, tenant_id, client_name, redirect_uris, scope, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, registration_flow, otp_template_id, front_channel_logout_uri, front_channel_logout_session_required, back_channel_logout_uri, logo_uri, policy_uri, tos_uri, consent_flow, dpop_bound_access_tokens, dpop_signing_algs, dpop_min_rsa_key_size, mtls_auth, certificate_bound_access_tokens, jwks, require_signed_request_object, require_pushed_authorization_requests, edge_signing_key)
        VALUES (${client.id}, ${client.tenantId}, ${client.clientName}, ${client.redirectUris}, ${client.scope},
                ${client.secret}, ${client.previousSecret}, ${client.accessTokenTtl}, ${client.refreshTokenTtl}, ${client.permissions}, ${client.theme}, ${client.authFlow}, ${client.registrationFlow}, ${client.otpTemplateId}, ${client.frontChannelLogoutUri}, ${client.frontChannelLogoutSessionRequired}, ${client.backChannelLogoutUri}, ${client.logoUri}, ${client.policyUri}, ${client.tosUri}, ${client.consentFlow}, ${client.dpopBoundAccessTokens}, ${client.dpopSigningAlgs}, ${client.dpopMinRsaKeySize}, ${client.mtlsAuth}, ${client.certificateBoundAccessTokens}, ${client.jwks}, ${client.requireSignedRequestObject}, ${client.requirePushedAuthorizationRequests}, ${client.edgeSigningKey})
      """.update.run()
    .unit
    .mapError {
      case e if PostgresOAuthClientRepository.isUniqueViolation(e) => ClientAlreadyExists(client.id)
      case e: Throwable                                            => e
    }

  override def updateClient(clientId: ClientId, patch: OAuthClientPatch): Task[Unit] =
    xa.transactMeasured("update-client"):
      // Lock the row (READ_COMMITTED + FOR UPDATE) to prevent lost updates from concurrent writers.
      val client = sql"""
        SELECT id, tenant_id, client_name, redirect_uris, scope, secret, previous_secret, access_token_ttl, refresh_token_ttl, permissions, theme, auth_flow, registration_flow, otp_template_id, front_channel_logout_uri, front_channel_logout_session_required, back_channel_logout_uri, logo_uri, policy_uri, tos_uri, consent_flow, dpop_bound_access_tokens, dpop_signing_algs, dpop_min_rsa_key_size, mtls_auth, certificate_bound_access_tokens, jwks, require_signed_request_object, require_pushed_authorization_requests, edge_signing_key
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
      val newRegistrationFlow = patch.registrationFlow.applyTo(client.registrationFlow)
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
          registration_flow = $newRegistrationFlow,
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
          edge_signing_key = $newEdgeSigningKey
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

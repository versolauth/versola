package versola.oauth.token

import versola.oauth.client.{OAuthConfigurationService, ResourceResolver}
import versola.oauth.client.model.{ClientCredentials, ClientId, ClientIdWithSecret, OAuthClientRecord, ResourceUri, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, AuthorizationCodeRecord, RefreshToken}
import versola.oauth.revoke.AccessTokenRevocationService
import versola.oauth.session.model.{RefreshAlreadyExchanged, RefreshTokenRecord, WithTtl}
import versola.oauth.session.SessionRepository
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError}
import versola.user.{UserRepository, UserRolesRepository}
import versola.util.{AuthPropertyGenerator, CoreConfig, MAC, Secret, SecurityService}
import zio.prelude.These
import zio.{Duration, IO, Task, ZIO, ZLayer}

trait OAuthTokenService:

  def exchangeAuthorizationCode(
      codeExchangeRequest: CodeExchangeRequest,
      tokenCredentials: ClientCredentials,
  ): IO[Throwable | TokenEndpointError, IssuedTokens]

  def refreshAccessToken(
      refreshTokenRequest: RefreshTokenRequest,
      tokenCredentials: ClientCredentials,
  ): IO[Throwable | TokenEndpointError, IssuedTokens]

  def clientCredentials(
      clientCredentialsRequest: ClientCredentialsRequest,
      tokenCredentials: ClientCredentials,
  ): IO[Throwable | TokenEndpointError, IssuedTokens]

object OAuthTokenService:
  /** Admin-console client; admin roles are only embedded in tokens issued for it. */
  val centralAdminClientId: ClientId = ClientId("central-admin")

  def live = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _))

  class Impl(
      authorizationCodeRepository: AuthorizationCodeRepository,
      oauthClientService: OAuthConfigurationService,
      sessionRepository: SessionRepository,
      accessTokenRevocationService: AccessTokenRevocationService,
      securityService: SecurityService,
      authPropertyGenerator: AuthPropertyGenerator,
      userRepository: UserRepository,
      userRolesRepository: UserRolesRepository,
      config: CoreConfig,
  ) extends OAuthTokenService:

    /** Completes the OAuth 2.0 Authorization Code exchange.
     * Propagates AMR and ACR from the authorization code record to the issued tokens.
     */
    override def exchangeAuthorizationCode(
        codeExchangeRequest: CodeExchangeRequest,
        tokenCredentials: ClientCredentials,
    ): IO[Throwable | TokenEndpointError, IssuedTokens] =
      import codeExchangeRequest.{code, codeVerifier, redirectUri}
      for
        client <- tokenCredentials match
          case ClientIdWithSecret(clientId, clientSecret) =>
            oauthClientService.verifySecret(clientId, clientSecret)
              .someOrFail(TokenEndpointError.InvalidClient)

        codeMac <- securityService.mac(Secret(code), config.security.authCodesSecret)

        codeRecord <- authorizationCodeRepository.find(codeMac)
          .someOrFail(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.clientId == client.id)(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.redirectUri == redirectUri)(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.verify(codeVerifier))(TokenEndpointError.InvalidGrant)

        _ <- authorizationCodeRepository.markAsUsed(codeMac).flatMap:
          case Left(at) =>
            accessTokenRevocationService.revoke(at) *>
              sessionRepository.deleteByAccessToken(at) *>
              ZIO.fail(TokenEndpointError.InvalidGrant)

          case Right(_) =>
            ZIO.unit

        now <- zio.Clock.instant
        accessToken = codeRecord.accessToken

        issuedTokens <- issueTokens(
          accessToken = accessToken,
          client = client,
          record = RefreshTokenRecord(
            sessionId = codeRecord.sessionId,
            publicSessionId = codeRecord.publicSessionId,
            accessToken = accessToken,
            userId = codeRecord.userId,
            clientId = codeRecord.clientId,
            audience = codeRecord.resources,
            scope = codeRecord.scope,
            issuedAt = now,
            expiresAt = now.plusSeconds(client.refreshTokenTtl.toSeconds),
            requestedClaims = codeRecord.requestedClaims,
            uiLocales = codeRecord.uiLocales,
            nonce = codeRecord.nonce,
            previousRefreshToken = None,
            amr = codeRecord.amr,
            authTime = codeRecord.authTime,
            acr = codeRecord.acr,
          ),
          accessTokenAudience = codeRecord.resources,
        ).mapError {
          case ex: Throwable => ex
          case _ => TokenEndpointError.InvalidGrant // illegal state
        }
      yield issuedTokens

    /** Refreshes an access token using a refresh token.
     * Preserves the original authentication context (AMR, ACR, authTime) from the refresh token record.
     */
    override def refreshAccessToken(
        refreshTokenRequest: RefreshTokenRequest,
        tokenCredentials: ClientCredentials,
    ): IO[Throwable | TokenEndpointError, IssuedTokens] =
      import refreshTokenRequest.{refreshToken, resources, scope}
      for
        client <- tokenCredentials match
          case ClientIdWithSecret(clientId, clientSecret) =>
            oauthClientService.verifySecret(clientId, clientSecret)
              .someOrFail(TokenEndpointError.InvalidClient)

        refreshTokenMac <- securityService.mac(Secret(refreshToken), config.security.refreshTokensSecret)

        tokenRecord <- sessionRepository.findToken(refreshTokenMac)
          .someOrFail(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.clientId == client.id)(TokenEndpointError.InvalidGrant)

        _ <- ZIO.fail(TokenEndpointError.InvalidScope)
          .when(scope.exists(!_.subsetOf(client.scope)))

        audience <- resolveTokenAudience(client, tokenRecord.audience, resources)

        now <- zio.Clock.instant

        accessToken <- authPropertyGenerator.nextAccessToken

        issuedTokens <- issueTokens(
          accessToken = accessToken,
          client = client,
          record = tokenRecord.copy(
            accessToken = accessToken,
            scope = scope.getOrElse(tokenRecord.scope),
            previousRefreshToken = Some(refreshTokenMac),
            issuedAt = now,
            expiresAt = now.plusSeconds(client.refreshTokenTtl.toSeconds),
          ),
          accessTokenAudience = audience,
        )
      yield issuedTokens

    private def resolveTokenAudience(
        client: OAuthClientRecord,
        granted: List[ResourceUri],
        requested: Option[List[ResourceUri]],
    ): IO[TokenEndpointError, List[ResourceUri]] =
      requested match
        case None =>
          ZIO.succeed(granted)
        case Some(resources) if resources.isEmpty =>
          ZIO.fail(TokenEndpointError.InvalidRequest)
        case Some(resources) =>
          val distinctResources = resources.distinct
          val invalidResource = distinctResources.find: resource =>
            !granted.contains(resource) &&
              !(granted.contains(ResourceResolver.EdgeResource) && isInternalResource(resource))

          invalidResource match
            case Some(resource) =>
              ZIO.fail(TokenEndpointError.InvalidTarget(resource))
            case None if distinctResources.exists(isInternalResource) && granted.contains(ResourceResolver.EdgeResource) =>
              ResourceResolver.resolve(oauthClientService, client, Some(distinctResources))
                .mapError(TokenEndpointError.InvalidTarget.apply)
            case None =>
              ZIO.succeed(distinctResources)

    private def isInternalResource(resource: ResourceUri): Boolean =
      resource != ResourceResolver.EdgeResource && ResourceUri.internalResourceId(resource).isDefined

    override def clientCredentials(
        request: ClientCredentialsRequest,
        tokenCredentials: ClientCredentials,
    ): IO[Throwable | TokenEndpointError, IssuedTokens] =
      for
        client <- tokenCredentials match
          case ClientIdWithSecret(clientId, clientSecret) =>
            oauthClientService.verifySecret(clientId, clientSecret)
              .someOrFail(TokenEndpointError.InvalidClient)

        _ <- ZIO.fail(TokenEndpointError.InvalidClient)
          .when(client.isPublic)

        _ <- ZIO.fail(TokenEndpointError.InvalidScope)
          .when(request.scope.exists(!_.subsetOf(client.scope)))

        _ <- ZIO.fail(TokenEndpointError.InvalidRequest)
          .when(request.resources.exists(_.isEmpty))

        audience <- ResourceResolver.resolve(oauthClientService, client, request.resources)
          .mapError(TokenEndpointError.InvalidTarget.apply)

        accessToken <- authPropertyGenerator.nextAccessToken
      yield IssuedTokens(
        accessToken = accessToken,
        clientId = client.id,
        audience = audience,
        accessTokenTtl = client.accessTokenTtl,
        userId = None,
        refreshToken = None,
        scope = request.scope.getOrElse(client.scope),
        requestedClaims = None,
        uiLocales = None,
        nonce = None,
        user = None,
        tenantId = client.tenantId,
        roles = Nil,
        sessionId = None,
        amr = Set.empty,
        authTime = None,
        acr = None,
      )

    /** Orchestrates token issuance for a specific authentication session.
     * populates AMR, ACR, and user roles based on the client and user record.
     */
    private def issueTokens(
        accessToken: AccessToken,
        client: OAuthClientRecord,
        record: RefreshTokenRecord,
        accessTokenAudience: List[ResourceUri],
    ): IO[Throwable | TokenEndpointError, IssuedTokens] =
      for
        refreshToken <- ZIO.when(record.scope.contains(ScopeToken.OfflineAccess))(
          for
            token <- authPropertyGenerator.nextRefreshToken
            mac <- securityService.mac(Secret(token), config.security.refreshTokensSecret)
            _ <- sessionRepository.createRefreshToken(mac, record)
              .mapError:
                case ex: Throwable => ex
                case _ => TokenEndpointError.InvalidGrant
          yield token,
        )

        // Fetch user if openid scope is present (needed for ID token generation)
        user <- ZIO.when(record.scope.contains(ScopeToken.OpenId))(
          userRepository.find(record.userId),
        )

        roles <- userRolesRepository.findRolesByUserAndTenant(record.userId, client.tenantId)
      yield IssuedTokens(
        accessToken = accessToken,
        clientId = record.clientId,
        audience = accessTokenAudience,
        accessTokenTtl = client.accessTokenTtl,
        userId = Some(record.userId),
        refreshToken = refreshToken,
        scope = record.scope,
        requestedClaims = record.requestedClaims,
        uiLocales = record.uiLocales,
        nonce = record.nonce,
        user = user.flatten,
        tenantId = client.tenantId,
        roles = roles,
        sessionId = Some(record.publicSessionId),
        amr = record.amr,
        authTime = Some(record.authTime),
        acr = record.acr,
      )
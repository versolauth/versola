package versola.oauth.token

import versola.oauth.client.{AuthorizationDetailResolver, OAuthConfigurationService, ResourceResolver}
import versola.oauth.client.model.{AuthorizationDetail, ClientCredentials, ClientId, ClientIdWithSecret, OAuthClientRecord, ResourceUri, ScopeToken, TenantId}
import versola.oauth.model.{AccessToken, AuthorizationCodeRecord, RefreshToken}
import versola.oauth.revoke.AccessTokenRevocationService
import versola.oauth.session.model.{RefreshAlreadyExchanged, RefreshTokenRecord, WithTtl}
import versola.oauth.session.SessionRepository
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError}
import versola.user.UserRepository
import versola.util.{AuthPropertyGenerator, Base64, CoreConfig, JsonSchemaValidator, MAC, Secret, SecurityService}
import versola.util.http.Observability
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
      schemaValidator: JsonSchemaValidator,
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
            Observability.setClientId(clientId) *>
              oauthClientService.verifySecret(clientId, clientSecret)
                .someOrFail(TokenEndpointError.InvalidClient)

        codeMac <- securityService.mac(Secret(code), config.security.authCodesSecret)

        codeRecord <- authorizationCodeRepository.find(codeMac)
          .someOrFail(TokenEndpointError.InvalidGrant.CodeNotFound)
          .filterOrFail(_.clientId == client.id)(TokenEndpointError.InvalidGrant.CodeClientMismatch)
          .filterOrFail(_.redirectUri == redirectUri)(TokenEndpointError.InvalidGrant.RedirectUriMismatch)
          .filterOrFail(_.verify(codeVerifier))(TokenEndpointError.InvalidGrant.PkceMismatch)

        _ <- Observability.setSessionId(codeRecord.publicSessionId)
        _ <- Observability.setUserId(codeRecord.userId.toString)

        _ <- authorizationCodeRepository.markAsUsed(codeMac).flatMap:
          case Left(at) =>
            zio.Clock.instant.flatMap: replayedAt =>
              // The replayed code's access token is not in hand here, only its id, so its
              // lifetime is bounded by the client's TTL rather than read from the token.
              accessTokenRevocationService.revoke(
                client = client,
                token = at,
                subject = codeRecord.userId.toString,
                expiresAt = replayedAt.plus(client.accessTokenTtl),
              )
            *>
              sessionRepository.deleteByAccessToken(at) *>
              ZIO.fail(TokenEndpointError.InvalidGrant.CodeReplayed)

          case Right(_) =>
            ZIO.unit

        now <- zio.Clock.instant
        accessToken = codeRecord.accessToken
        _ <- Observability.setToken(accessToken.encoded)

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
            authorizationDetails = codeRecord.authorizationDetails,
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
          accessTokenAuthorizationDetails = codeRecord.authorizationDetails.getOrElse(Nil),
        ).mapError {
          case ex: Throwable => ex
          case _ => TokenEndpointError.InvalidGrant.RefreshChainAlreadyExchanged // illegal state
        }
      yield issuedTokens

    /** Refreshes an access token using a refresh token.
     * Preserves the original authentication context (AMR, ACR, authTime) from the refresh token record.
     */
    override def refreshAccessToken(
        refreshTokenRequest: RefreshTokenRequest,
        tokenCredentials: ClientCredentials,
    ): IO[Throwable | TokenEndpointError, IssuedTokens] =
      import refreshTokenRequest.{authorizationDetails, refreshToken, resources, scope}
      for
        _ <- Observability.setPreviousRefreshToken(Base64.urlEncode(refreshToken))

        client <- tokenCredentials match
          case ClientIdWithSecret(clientId, clientSecret) =>
            Observability.setClientId(clientId) *>
              oauthClientService.verifySecret(clientId, clientSecret)
                .someOrFail(TokenEndpointError.InvalidClient)

        refreshTokenMac <- securityService.mac(Secret(refreshToken), config.security.refreshTokensSecret)

        tokenRecord <- sessionRepository.findToken(refreshTokenMac).flatMap:
          case Some(record) if record.clientId == client.id =>
            ZIO.succeed(record)
          case Some(_) =>
            ZIO.fail(TokenEndpointError.InvalidGrant.RefreshTokenClientMismatch)
          case None =>
            detectReplay(client, refreshTokenMac).flatMap: replayed =>
              ZIO.fail:
                if replayed then TokenEndpointError.InvalidGrant.RefreshTokenReplayed
                else TokenEndpointError.InvalidGrant.RefreshTokenNotFound

        _ <- Observability.setSessionId(tokenRecord.publicSessionId)
        _ <- Observability.setUserId(tokenRecord.userId.toString)

        // RFC 6749 §6: the request may narrow the underlying grant but never widen it, so the
        // comparison is against what was granted, not against the client's registration —
        // which would hand back every scope the user deselected at consent on the first refresh.
        _ <- ZIO.fail(TokenEndpointError.InvalidScope)
          .when(scope.exists(!_.subsetOf(tokenRecord.scope)))

        audience <- resolveTokenAudience(client, tokenRecord.audience, resources)

        details <- resolveTokenAuthorizationDetails(client, tokenRecord.authorizationDetails.getOrElse(Nil), authorizationDetails)

        now <- zio.Clock.instant

        accessToken <- authPropertyGenerator.nextAccessToken
        _ <- Observability.setToken(accessToken.encoded)

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
          accessTokenAuthorizationDetails = details,
        )
      yield issuedTokens

    /** RFC 9700 §4.14.2: a refresh token presented after it was already rotated away means the
      * chain leaked -- to an attacker who redeemed it first, or back to its rightful owner
      * after an attacker's redemption already won the rotation race. Either way neither party
      * can be trusted with the chain's live end anymore, so it is expired and its access token
      * pushed to the client's back channel, forcing a full re-authorization instead of leaving
      * a live session for whoever asks next.
      *
      * Scoped to this refresh-token chain, not the wider SSO session: one client's leak
      * should not log the user out of every other client sharing the session, and a chain
      * that has already rotated past this token twice was already a dead end, so nothing is
      * revoked (there is nothing left alive to protect).
      *
      * Returns whether a chain was found and revoked, so the caller can tell a replay apart
      * from a token that never existed -- both fail the request identically, but only the
      * former is worth surfacing in the request's error context.
      */
    private def detectReplay(client: OAuthClientRecord, replayed: MAC.Of[RefreshToken]): Task[Boolean] =
      sessionRepository.markChainReplayed(replayed, client.id).flatMap:
        case Some((userId, accessToken)) =>
          zio.Clock.instant.flatMap: now =>
            // As with authorization-code replay, the live access token itself is not in
            // hand here, only its id, so its lifetime is bounded by the client's TTL.
            accessTokenRevocationService.revoke(
              client = client,
              token = accessToken,
              subject = userId.toString,
              expiresAt = now.plus(client.accessTokenTtl),
            )
          .as(true)
        case None =>
          ZIO.succeed(false)

    /** RFC 9396 §6: a token request may ask for the authorization details of the underlying
      * grant or fewer of them, never for more; §6.1 compares the requested objects with the
      * granted ones. Comparison is on the whole object (key order normalized), so a client
      * may drop details but not alter one — altering it would be asking for something that
      * was never granted.
      */
    private def resolveTokenAuthorizationDetails(
        client: OAuthClientRecord,
        granted: List[AuthorizationDetail],
        requested: Option[List[AuthorizationDetail]],
    ): IO[TokenEndpointError, List[AuthorizationDetail]] =
      requested match
        case None =>
          ZIO.succeed(granted)
        case Some(details) if details.isEmpty =>
          ZIO.fail(TokenEndpointError.InvalidRequest)
        case Some(details) =>
          val grantedCanonical = granted.map(_.canonical).toSet
          details.find(detail => !grantedCanonical.contains(detail.canonical)) match
            case Some(detail) =>
              ZIO.fail(TokenEndpointError.InvalidAuthorizationDetails(
                s"${detail.`type`} was not granted by the underlying grant",
              ))
            case None =>
              AuthorizationDetailResolver.resolve(oauthClientService, schemaValidator, client, details)
                .mapError(rejected =>
                  TokenEndpointError.InvalidAuthorizationDetails(s"${rejected.`type`} - ${rejected.reason}"),
                )

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
            Observability.setClientId(clientId) *>
              oauthClientService.verifySecret(clientId, clientSecret)
                .someOrFail(TokenEndpointError.InvalidClient)

        _ <- ZIO.fail(TokenEndpointError.InvalidClient)
          .when(client.isPublic)

        _ <- ZIO.fail(TokenEndpointError.InvalidScope)
          .when(request.scope.exists(!_.subsetOf(client.scope)))

        _ <- ZIO.fail(TokenEndpointError.InvalidRequest)
          .when(request.resources.exists(_.isEmpty))

        _ <- ZIO.fail(TokenEndpointError.InvalidRequest)
          .when(request.authorizationDetails.exists(_.isEmpty))

        audience <- ResourceResolver.resolve(oauthClientService, client, request.resources)
          .mapError(TokenEndpointError.InvalidTarget.apply)

        // No underlying grant exists for client_credentials (RFC 9396 §6), so the requested
        // details are checked against the tenant's registry alone.
        details <- AuthorizationDetailResolver
          .resolve(oauthClientService, schemaValidator, client, request.authorizationDetails.getOrElse(Nil))
          .mapError(rejected =>
            TokenEndpointError.InvalidAuthorizationDetails(s"${rejected.`type`} - ${rejected.reason}"),
          )

        accessToken <- authPropertyGenerator.nextAccessToken
        _ <- Observability.setToken(accessToken.encoded)
      yield IssuedTokens(
        accessToken = accessToken,
        clientId = client.id,
        audience = audience,
        authorizationDetails = details,
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
        accessTokenAuthorizationDetails: List[AuthorizationDetail],
    ): IO[Throwable | TokenEndpointError, IssuedTokens] =
      for
        refreshToken <- ZIO.when(record.scope.contains(ScopeToken.OfflineAccess))(
          for
            token <- authPropertyGenerator.nextRefreshToken
            _ <- Observability.setRefreshToken(Base64.urlEncode(token))
            mac <- securityService.mac(Secret(token), config.security.refreshTokensSecret)
            _ <- sessionRepository.createRefreshToken(mac, record)
              .mapError:
                case ex: Throwable => ex
                case _ => TokenEndpointError.InvalidGrant.RefreshChainAlreadyExchanged
          yield token,
        )

        // Fetch user if openid scope is present (needed for ID token generation)
        user <- ZIO.when(record.scope.contains(ScopeToken.OpenId))(
          userRepository.find(record.userId),
        )

        roles <- userRepository.findRolesByUserAndTenant(record.userId, client.tenantId)
      yield IssuedTokens(
        accessToken = accessToken,
        clientId = record.clientId,
        audience = accessTokenAudience,
        authorizationDetails = accessTokenAuthorizationDetails,
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

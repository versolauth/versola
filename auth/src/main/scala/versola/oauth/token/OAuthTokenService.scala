package versola.oauth.token

import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{OAuthClientRecord, ScopeToken}
import versola.oauth.model.{AccessToken, AuthorizationCodeRecord, RefreshToken}
import versola.oauth.session.model.{RefreshAlreadyExchanged, RefreshTokenRecord, WithTtl}
import versola.oauth.session.{RefreshTokenRepository, SessionRepository}
import versola.oauth.token.model.{ClientCredentialsRequest, CodeExchangeRequest, IssuedTokens, RefreshTokenRequest, TokenEndpointError}
import versola.util.http.{ClientCredentials, ClientIdWithSecret}
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
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  class Impl(
      authorizationCodeRepository: AuthorizationCodeRepository,
      oauthClientService: OAuthClientService,
      tokenRepository: RefreshTokenRepository,
      securityService: SecurityService,
      authPropertyGenerator: AuthPropertyGenerator,
      config: CoreConfig,
  ) extends OAuthTokenService:

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

        codeMac <- securityService.mac(Secret(code), config.security.authCodes.pepper)

        codeRecord <- authorizationCodeRepository.find(codeMac)
          .someOrFail(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.clientId == client.id)(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.redirectUri == redirectUri)(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.verify(codeVerifier))(TokenEndpointError.InvalidGrant)

        _ <- authorizationCodeRepository.delete(codeMac)
        now <- zio.Clock.instant

        accessToken <- authPropertyGenerator.nextAccessToken

        issuedTokens <- issueTokens(
          accessToken = accessToken,
          client = client,
          record = RefreshTokenRecord(
            sessionId = codeRecord.sessionId,
            accessToken = accessToken,
            userId = codeRecord.userId,
            clientId = codeRecord.clientId,
            externalAudience = client.externalAudience,
            scope = codeRecord.scope,
            issuedAt = now,
            expiresAt = now.plusSeconds(client.accessTokenTtl.toSeconds),
            requestedClaims = codeRecord.requestedClaims,
            uiLocales = codeRecord.uiLocales,
            previousRefreshToken = None,
          ),
        ).mapError {
          case ex: Throwable => ex
          case _ => TokenEndpointError.InvalidGrant // illegal state
        }
      yield issuedTokens

    override def refreshAccessToken(
        refreshTokenRequest: RefreshTokenRequest,
        tokenCredentials: ClientCredentials,
    ): IO[Throwable | TokenEndpointError, IssuedTokens] =
      import refreshTokenRequest.{refreshToken, scope}
      for
        client <- tokenCredentials match
          case ClientIdWithSecret(clientId, clientSecret) =>
            oauthClientService.verifySecret(clientId, clientSecret)
              .someOrFail(TokenEndpointError.InvalidClient)

        refreshTokenMac <- securityService.mac(Secret(refreshToken), config.security.refreshTokens.pepper)

        tokenRecord <- tokenRepository.find(refreshTokenMac)
          .someOrFail(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.clientId == client.id)(TokenEndpointError.InvalidGrant)

        _ <- ZIO.fail(TokenEndpointError.InvalidScope)
          .when(scope.exists(!_.subsetOf(client.scope)))

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
            expiresAt = now.plusSeconds(config.security.refreshTokens.ttl.toSeconds),
          ),
        )
      yield issuedTokens

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

        accessToken <- authPropertyGenerator.nextAccessToken
      yield IssuedTokens(
        accessToken = accessToken,
        clientId = client.id,
        audience = client.audience,
        accessTokenTtl = client.accessTokenTtl,
        userId = None,
        refreshToken = None,
        scope = request.scope.getOrElse(client.scope),
        requestedClaims = None,
        uiLocales = None,
      )

    private def issueTokens(
        accessToken: AccessToken,
        client: OAuthClientRecord,
        record: RefreshTokenRecord,
    ): IO[Throwable | TokenEndpointError, IssuedTokens] =
      for
        refreshToken <- ZIO.when(record.scope.contains(ScopeToken.OfflineAccess))(
          for
            token <- authPropertyGenerator.nextRefreshToken
            mac <- securityService.mac(Secret(token), config.security.refreshTokens.pepper)
            _ <- tokenRepository.create(mac, record)
              .mapError:
                case ex: Throwable => ex
                case _ => TokenEndpointError.InvalidGrant
          yield token,
        )
      yield IssuedTokens(
        accessToken = accessToken,
        clientId = record.clientId,
        audience = client.audience,
        accessTokenTtl = client.accessTokenTtl,
        userId = Some(record.userId),
        refreshToken = refreshToken,
        scope = record.scope,
        requestedClaims = record.requestedClaims,
        uiLocales = record.uiLocales,
      )

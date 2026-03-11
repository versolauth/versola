package versola.oauth.token

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{OAuthClientRecord, ScopeToken}
import versola.oauth.model.AuthorizationCodeRecord
import versola.oauth.session.model.{TokenCreationRecord, WithTtl}
import versola.oauth.session.{SessionRepository, RefreshTokenRepository}
import versola.oauth.token.model.{CodeExchangeRequest, IssuedTokens, TokenEndpointError}
import versola.util.http.{ClientCredentials, ClientIdWithSecret}
import versola.util.{AuthPropertyGenerator, CoreConfig, MAC, Secret, SecurityService}
import zio.prelude.These
import zio.{Duration, IO, Task, ZIO, ZLayer}

trait OAuthTokenService:

  def exchangeAuthorizationCode(
      codeExchangeRequest: CodeExchangeRequest,
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

        codeMac <- securityService.macBlake3(Secret(code), config.security.authCodes.pepper)

        codeRecord <- authorizationCodeRepository.find(codeMac)
          .someOrFail(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.clientId == client.id)(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.redirectUri == redirectUri)(TokenEndpointError.InvalidGrant)
          .filterOrFail(_.verify(codeVerifier))(TokenEndpointError.InvalidGrant)

        _ <- authorizationCodeRepository.delete(codeMac)
        issuedTokens <- issueTokens(client, codeRecord)
      yield issuedTokens

    private def issueTokens(
        client: OAuthClientRecord,
        codeRecord: AuthorizationCodeRecord,
    ): Task[IssuedTokens] =
      for
        accessToken <- authPropertyGenerator.nextAccessToken

        hasOfflineAccess = codeRecord.scope.contains(ScopeToken.OfflineAccess)

        now <- zio.Clock.instant

        refreshTokenWithMac <- ZIO.when(hasOfflineAccess)(
          for
            token <- authPropertyGenerator.nextRefreshToken
            mac <- securityService.macBlake3(Secret(token), config.security.refreshTokens.pepper)
          yield (token, mac),
        )

        accessTokenTtl = client.accessTokenTtl

        tokenRecord = TokenCreationRecord(
          sessionId = codeRecord.sessionId,
          userId = codeRecord.userId,
          clientId = codeRecord.clientId,
          scope = codeRecord.scope,
          issuedAt = now,
        )

        _ <- ZIO.foreach(refreshTokenWithMac) { case (_, mac) =>
          tokenRepository.create(mac, config.security.refreshTokens.ttl, tokenRecord)
        }
      yield IssuedTokens(
        accessToken = accessToken,
        accessTokenTtl = accessTokenTtl,
        accessTokenJwtProperties = Some(IssuedTokens.JwtProperties(codeRecord.userId)),
        refreshToken = refreshTokenWithMac.map(_._1),
        scope = codeRecord.scope,
      )

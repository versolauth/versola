package versola.oauth.token

import versola.auth.TokenService
import versola.auth.model.TokenType
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{ClientId, ClientSecret, ScopeToken}
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord, CodeVerifier}
import versola.oauth.token.model.{ClientIdWithSecret, CodeExchangeRequest, TokenCredentials, TokenEndpointError, TokenResponse}
import versola.security.{Secret, SecurityService}
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.CoreConfig
import zio.{Clock, IO, Task, ZIO, ZLayer}

trait OAuthTokenService:

  def exchangeAuthorizationCode(
      codeExchangeRequest: CodeExchangeRequest,
      tokenCredentials: TokenCredentials,
  ): IO[Throwable | TokenEndpointError, TokenResponse]

object OAuthTokenService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  class Impl(
      authorizationCodeRepository: AuthorizationCodeRepository,
      oauthClientService: OAuthClientService,
      userRepository: UserRepository,
      tokenService: TokenService,
      securityService: SecurityService,
      config: CoreConfig
  ) extends OAuthTokenService:

    override def exchangeAuthorizationCode(
        codeExchangeRequest: CodeExchangeRequest,
        tokenCredentials: TokenCredentials,
    ): IO[Throwable | TokenEndpointError, TokenResponse] =
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

        accessToken = stubAccessToken
        refreshToken = stubRefreshToken

      yield TokenResponse(
        accessToken = accessToken,
        tokenType = TokenResponse.TokenType,
        expiresIn = TokenType.AccessToken.ttl.toSeconds,
        refreshToken = Some(refreshToken),
        scope = formatScope(codeRecord.scope),
      )

    private def formatScope(scope: Set[ScopeToken]): Option[String] =
      Option.when(scope.nonEmpty)(scope.mkString(" "))

    // Stub values - will be replaced when TokenService is integrated
    private val stubAccessToken = "stub_access_token"
    private val stubRefreshToken = "stub_refresh_token"


package versola.oauth.introspect

import versola.auth.model.RefreshToken
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.OAuthClientRecord
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.model.AccessToken
import versola.oauth.session.RefreshTokenRepository
import versola.oauth.session.model.TokenRecord
import versola.util.http.{ClientCredentials, ClientIdWithSecret}
import versola.util.{CoreConfig, Secret, SecurityService}
import zio.{IO, ZIO, ZLayer}

trait IntrospectionService:
  def introspectAccessToken(
                             token: AccessToken,
                             credentials: ClientCredentials,
  ): IO[IntrospectionError, IntrospectionResponse]

  def introspectRefreshToken(
      token: RefreshToken,
      credentials: ClientCredentials,
  ): IO[Throwable | IntrospectionError, IntrospectionResponse]

object IntrospectionService:
  def live: ZLayer[
    OAuthClientService & RefreshTokenRepository & SecurityService & CoreConfig,
    Nothing,
    IntrospectionService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      oauthClientService: OAuthClientService,
      tokenRepository: RefreshTokenRepository,
      securityService: SecurityService,
      config: CoreConfig,
  ) extends IntrospectionService:

    override def introspectAccessToken(
                                        token: AccessToken,
                                        credentials: ClientCredentials,
    ): IO[IntrospectionError, IntrospectionResponse] =
      for
        _ <- authenticateClient(credentials)
      yield buildJwtIntrospectionResponse(token)

    private def buildJwtIntrospectionResponse(token: AccessToken): IntrospectionResponse =
      IntrospectionResponse(
        active = true,
        clientId = Some(token.clientId),
        scope = Some(token.scope.mkString(" ")),
        username = None,
        tokenType = Some("Bearer"),
        exp = Some(token.expiresAt.getEpochSecond),
        iat = Some(token.issuedAt.getEpochSecond),
        nbf = token.notBefore.map(_.getEpochSecond),
        sub = Some(token.userId),
        aud = Some(token.audience),
        iss = token.issuer,
        jti = token.jwtId,
      )

    override def introspectRefreshToken(
        token: RefreshToken,
        credentials: ClientCredentials,
    ): IO[Throwable | IntrospectionError, IntrospectionResponse] =
      for
        _ <- authenticateClient(credentials)
        tokenMac <- securityService.macBlake3(Secret(token), config.security.refreshTokens.pepper)
        tokenRecord <- tokenRepository.findRefreshToken(tokenMac)
      yield buildIntrospectionResponse(tokenRecord)

    private def authenticateClient(
        credentials: ClientCredentials,
    ): IO[IntrospectionError, OAuthClientRecord] =
      credentials match
        case ClientIdWithSecret(clientId, clientSecret) =>
          oauthClientService.verifySecret(clientId, clientSecret)
            .someOrFail(IntrospectionError.Unauthenticated())

    private def buildIntrospectionResponse(record: Option[TokenRecord]): IntrospectionResponse =
      record match
        case Some(record) =>
          IntrospectionResponse(
            active = true,
            scope = Some(record.scope.mkString(" ")),
            clientId = Some(record.clientId),
            sub = Some(record.userId),
            tokenType = Some("Bearer"),
            username = None,
            exp = Some(record.expiresAt.getEpochSecond),
            nbf = None,
            iss = Some(config.jwt.issuer),
            jti = None,
            iat = Some(record.issuedAt.getEpochSecond),
            aud = None,
          )
        case None =>
          IntrospectionResponse.Inactive

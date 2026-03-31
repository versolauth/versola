package versola.oauth.introspect

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientCredentials, ClientIdWithSecret, OAuthClientRecord}
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.session.RefreshTokenRepository
import versola.oauth.session.model.RefreshTokenRecord
import versola.util.{CoreConfig, Secret, SecurityService}
import zio.{IO, ZIO, ZLayer}

trait IntrospectionService:
  def introspectAccessToken(
      token: AccessTokenPayload,
      credentials: ClientCredentials,
  ): IO[Throwable | IntrospectionError, IntrospectionResponse]

  def introspectRefreshToken(
      token: RefreshToken,
      credentials: ClientCredentials,
  ): IO[Throwable | IntrospectionError, IntrospectionResponse]

object IntrospectionService:
  def live: ZLayer[
    OAuthConfigurationService & RefreshTokenRepository & SecurityService & CoreConfig,
    Nothing,
    IntrospectionService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
              oauthClientService: OAuthConfigurationService,
              tokenRepository: RefreshTokenRepository,
              securityService: SecurityService,
              config: CoreConfig,
  ) extends IntrospectionService:

    override def introspectAccessToken(
        token: AccessTokenPayload,
        credentials: ClientCredentials,
    ): IO[Throwable | IntrospectionError, IntrospectionResponse] =
      for
        client <- authenticateClient(credentials)

        _ <- ZIO.fail(IntrospectionError.Unauthenticated)
          .when(!token.audience.contains(client.id))
      yield buildJwtIntrospectionResponse(token)

    private def buildJwtIntrospectionResponse(token: AccessTokenPayload): IntrospectionResponse =
      IntrospectionResponse(
        active = true,
        clientId = Some(token.clientId),
        scope = Some(token.scope.mkString(" ")),
        username = None,
        tokenType = Some("Bearer"),
        exp = Some(token.expiresAt.getEpochSecond),
        iat = Some(token.issuedAt.getEpochSecond),
        nbf = token.notBefore.map(_.getEpochSecond),
        sub = Some(token.subject),
        aud = Some(token.audience),
        iss = Some(token.issuer),
        jti = Some(token.id.encoded),
      )

    override def introspectRefreshToken(
        token: RefreshToken,
        credentials: ClientCredentials,
    ): IO[Throwable | IntrospectionError, IntrospectionResponse] =
      for
        client <- authenticateClient(credentials)
        tokenMac <- securityService.mac(Secret(token), config.security.refreshTokens.pepper)
        tokenRecord <- tokenRepository.find(tokenMac)

        _ <- ZIO.fail(IntrospectionError.Unauthenticated)
          .when(tokenRecord.exists(_.clientId != client.id))
      yield buildIntrospectionResponse(tokenRecord)

    private def authenticateClient(
        credentials: ClientCredentials,
    ): IO[IntrospectionError, OAuthClientRecord] =
      credentials match
        case ClientIdWithSecret(clientId, clientSecret) =>
          oauthClientService.verifySecret(clientId, clientSecret)
            .someOrFail(IntrospectionError.InvalidClient)

    private def buildIntrospectionResponse(record: Option[RefreshTokenRecord]): IntrospectionResponse =
      record match
        case Some(record) =>
          IntrospectionResponse(
            active = true,
            scope = Some(record.scope.mkString(" ")),
            clientId = Some(record.clientId),
            sub = Some(record.userId.toString),
            tokenType = Some("Bearer"),
            username = None,
            exp = Some(record.expiresAt.getEpochSecond),
            nbf = None,
            iss = Some(config.jwt.issuer),
            iat = Some(record.issuedAt.getEpochSecond),
            aud = Some((record.clientId :: record.externalAudience).toVector),
            jti = None,
          )
        case None =>
          IntrospectionResponse.Inactive

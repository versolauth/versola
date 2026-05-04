package versola.oauth.revoke

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientCredentials, ClientIdWithSecret, OAuthClientRecord}
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.revoke.model.RevocationError
import versola.oauth.session.RefreshTokenRepository
import versola.util.{CoreConfig, Secret, SecurityService}
import zio.{IO, Task, ZIO, ZLayer}

trait RevocationService:
  def revokeRefreshToken(
      token: RefreshToken,
      credentials: ClientCredentials,
  ): IO[Throwable | RevocationError, Unit]

  def revokeAccessToken(
      token: AccessTokenPayload,
      credentials: ClientCredentials,
  ): IO[Throwable | RevocationError, Unit]

object RevocationService:
  def live: ZLayer[
    OAuthConfigurationService & RefreshTokenRepository & AccessTokenRevocationService & SecurityService & CoreConfig,
    Nothing,
    RevocationService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _))

  private class Impl(
                      oauthClientService: OAuthConfigurationService,
                      tokenRepository: RefreshTokenRepository,
                      accessTokenRevocationService: AccessTokenRevocationService,
                      securityService: SecurityService,
                      config: CoreConfig,
  ) extends RevocationService:

    override def revokeRefreshToken(
        token: RefreshToken,
        credentials: ClientCredentials,
    ): IO[Throwable | RevocationError, Unit] =
      for
        client <- authenticateClient(credentials)
        tokenMac <- securityService.mac(Secret(token), config.security.refreshTokens.pepper)
        tokenRecord <- tokenRepository.find(tokenMac)

        _ <- ZIO.fail(RevocationError.InvalidClient)
          .when(tokenRecord.exists(_.clientId != client.id))

        _ <- tokenRecord match
          case None =>
            ZIO.unit
          case Some(record) =>
            tokenRepository.delete(tokenMac) *>
              accessTokenRevocationService.revoke(record.accessToken)
      yield ()

    override def revokeAccessToken(
        token: AccessTokenPayload,
        credentials: ClientCredentials,
    ): IO[Throwable | RevocationError, Unit] =
      for
        client <- authenticateClient(credentials)
        _ <- ZIO.fail(RevocationError.InvalidClient)
          .when(!token.clientId.contains(client.id))

        _ <- accessTokenRevocationService.revoke(token.id)
      yield ()

    private def authenticateClient(
        credentials: ClientCredentials,
    ): IO[RevocationError, OAuthClientRecord] =
      credentials match
        case ClientIdWithSecret(clientId, clientSecret) =>
          oauthClientService.verifySecret(clientId, clientSecret)
            .someOrFail(RevocationError.InvalidClient)
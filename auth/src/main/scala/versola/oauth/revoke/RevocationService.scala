package versola.oauth.revoke

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientCredentials, ClientIdWithSecret, OAuthClientRecord}
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.revoke.model.RevocationError
import versola.oauth.session.SessionRepository
import versola.util.{CoreConfig, Secret, SecurityService}
import zio.{Clock, IO, Task, ZIO, ZLayer}

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
    OAuthConfigurationService & SessionRepository & AccessTokenRevocationService & SecurityService & CoreConfig,
    Nothing,
    RevocationService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _))

  private class Impl(
                      oauthClientService: OAuthConfigurationService,
                      sessionRepository: SessionRepository,
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
        tokenMac <- securityService.mac(Secret(token), config.security.refreshTokensSecret)
        tokenRecord <- sessionRepository.findToken(tokenMac)

        _ <- ZIO.fail(RevocationError.InvalidClient)
          .when(tokenRecord.exists(_.clientId != client.id))

        _ <- tokenRecord match
          case None =>
            ZIO.unit
          case Some(record) =>
            for
              _ <- sessionRepository.delete(tokenMac)
              now <- Clock.instant
              // The access token itself was not presented here, so its real `exp` is not at
              // hand. `exp = iat + accessTokenTtl` and `iat <= now`, so this over-retains the
              // revocation by at most one TTL and never under-retains it.
              _ <- accessTokenRevocationService.revoke(
                client = client,
                token = record.accessToken,
                subject = record.userId.toString,
                expiresAt = now.plus(client.accessTokenTtl),
              )
            yield ()
      yield ()

    override def revokeAccessToken(
        token: AccessTokenPayload,
        credentials: ClientCredentials,
    ): IO[Throwable | RevocationError, Unit] =
      for
        client <- authenticateClient(credentials)
        _ <- ZIO.fail(RevocationError.InvalidClient)
          .when(!token.clientId.contains(client.id))

        // The token was presented and parsed, so its own `exp` is exact.
        _ <- accessTokenRevocationService.revoke(
          client = client,
          token = token.id,
          subject = token.subject,
          expiresAt = token.expiresAt,
        )
      yield ()

    private def authenticateClient(
        credentials: ClientCredentials,
    ): IO[RevocationError, OAuthClientRecord] =
      credentials match
        case ClientIdWithSecret(clientId, clientSecret) =>
          oauthClientService.verifySecret(clientId, clientSecret)
            .someOrFail(RevocationError.InvalidClient)
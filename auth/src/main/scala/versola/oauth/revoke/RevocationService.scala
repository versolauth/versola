package versola.oauth.revoke

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientCredentials, ClientIdWithSecret, OAuthClientRecord}
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.revoke.model.RevocationError
import versola.oauth.session.SessionRepository
import versola.util.{CoreConfig, Secret, SecurityService}
import zio.{Clock, IO, NonEmptyChunk, Task, ZIO, ZLayer}

trait RevocationService:
  def revokeRefreshToken(
      token: RefreshToken,
      credentials: ClientCredentials,
  ): IO[Throwable | RevocationError, Unit]

  def revokeAccessToken(
      token: AccessTokenPayload,
      credentials: ClientCredentials,
  ): IO[Throwable | RevocationError, Unit]

  /** Validates the client credentials alone, with no token involved. RFC 7009 §2.1 requires the
    * server to authenticate the client independently of whether the presented token turns out to
    * be one it could ever have issued -- so a value no client could hold must still fail with
    * `InvalidClient` if the credentials presenting it are themselves wrong.
    */
  def authenticateClient(credentials: ClientCredentials): IO[RevocationError, OAuthClientRecord]

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
            // No access token was presented here, and none is recorded against the chain, so
            // the push names the family every token this grant issued carries. The bound is
            // the client's current `accessTokenTtl` from now: an approximation, and the only
            // one available once the tokens themselves are not being named.
            Clock.instant.flatMap: revokedAt =>
              sessionRepository.delete(tokenMac) *>
                accessTokenRevocationService.revokeFamily(
                  client = client,
                  family = record.familyId,
                  subject = record.userId.toString,
                  expiresAt = revokedAt.plus(client.accessTokenTtl),
                )
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
          tokens = NonEmptyChunk(token.id),
          subject = token.subject,
          expiresAt = token.expiresAt,
        )
      yield ()

    override def authenticateClient(
        credentials: ClientCredentials,
    ): IO[RevocationError, OAuthClientRecord] =
      credentials match
        case ClientIdWithSecret(clientId, clientSecret) =>
          oauthClientService.verifySecret(clientId, clientSecret)
            .someOrFail(RevocationError.InvalidClient)
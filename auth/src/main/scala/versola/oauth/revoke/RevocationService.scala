package versola.oauth.revoke

import versola.audit.AuditSupport.*
import versola.audit.AuthAuditDetails
import versola.audit.{AuthActors, AuthAuditKinds}
import versola.oauth.client.model.{ClientCredentials, OAuthClientRecord}
import versola.oauth.clientauth.{AuthenticatedEndpoint, ClientAuthentication}
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.mtls.ClientCertificate
import versola.oauth.revoke.model.RevocationError
import versola.oauth.session.SessionRepository
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.{Base64, CoreConfig, Secret, SecurityService}
import zio.{Clock, IO, NonEmptyChunk, Task, ZIO, ZLayer}

import java.util.UUID
import scala.util.Try

trait RevocationService:
  def revokeRefreshToken(
      token: RefreshToken,
      credentials: ClientCredentials,
      certificate: Option[ClientCertificate],
  ): IO[Throwable | RevocationError, Unit]

  def revokeAccessToken(
      token: AccessTokenPayload,
      credentials: ClientCredentials,
      certificate: Option[ClientCertificate],
  ): IO[Throwable | RevocationError, Unit]

  /** Validates the client credentials alone, with no token involved. RFC 7009 §2.1 requires the
    * server to authenticate the client independently of whether the presented token turns out to
    * be one it could ever have issued -- so a value no client could hold must still fail with
    * `InvalidClient` if the credentials presenting it are themselves wrong.
    */
  def authenticateClient(
      credentials: ClientCredentials,
      certificate: Option[ClientCertificate],
  ): IO[Throwable | RevocationError, OAuthClientRecord]

object RevocationService:
  def live: ZLayer[
    ClientAuthentication & SessionRepository & AccessTokenRevocationService & SecurityService & CoreConfig & AuditEvents,
    Nothing,
    RevocationService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  private class Impl(
      clientAuthentication: ClientAuthentication,
      sessionRepository: SessionRepository,
      accessTokenRevocationService: AccessTokenRevocationService,
      securityService: SecurityService,
      config: CoreConfig,
      audit: AuditEvents,
  ) extends RevocationService:

    private def draft(client: OAuthClientRecord, userId: Option[UUID], details: AuthAuditDetails.TokenRevoked): AuditDraft =
      AuditDraft(
        kind = AuthAuditKinds.TokenRevoked,
        outcome = AuditOutcome.success,
        actor = Some((AuthActors.client, Some(client.id))),
        userId = userId,
        details = details,
      )

    override def revokeRefreshToken(
        token: RefreshToken,
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
    ): IO[Throwable | RevocationError, Unit] =
      for
        client <- authenticateClient(credentials, certificate)
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
              // The event is inserted in the transaction that revokes the token, and only when the token was live: a repeated
              // revocation of the same token (RFC 7009 makes it a success) writes no row.
              audit.atomically(
                draft(
                  client,
                  Some(record.userId),
                  AuthAuditDetails.TokenRevoked(
                    tokenType = "refresh_token",
                    jti = None,
                    refreshPrefix = Some(Base64.urlEncode(token).take(9)),
                    cause = "rfc7009",
                  ),
                ),
              )(sessionRepository.delete(tokenMac, _)) *>
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
        certificate: Option[ClientCertificate],
    ): IO[Throwable | RevocationError, Unit] =
      for
        client <- authenticateClient(credentials, certificate)
        _ <- ZIO.fail(RevocationError.InvalidClient)
          .when(!token.clientId.contains(client.id))

        // The token was presented and parsed, so its own `exp` is exact.
        _ <- accessTokenRevocationService.revoke(
          client = client,
          tokens = NonEmptyChunk(token.id),
          subject = token.subject,
          expiresAt = token.expiresAt,
        )
        // An access token is revoked by telling the edges, with nothing of auth's own to change: queued.
        _ <- audit.log(
          draft(
            client,
            Try(UUID.fromString(token.subject)).toOption,
            AuthAuditDetails.TokenRevoked(tokenType = "access_token", jti = Some(Base64.urlEncode(token.id)), refreshPrefix = None, cause = "rfc7009"),
          ),
        )
      yield ()

    /** RFC 8705 §2.1: a client that registered an mTLS subject holds no secret and
      * authenticates with its certificate instead. */
    override def authenticateClient(
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
    ): IO[Throwable | RevocationError, OAuthClientRecord] =
      clientAuthentication.authenticate(
        credentials = credentials,
        certificate = certificate,
        endpoint = AuthenticatedEndpoint.Revocation,
      ).mapError {
        case error: Throwable => error
        case _ => RevocationError.InvalidClient
      }

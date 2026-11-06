package versola.oauth.revoke

import versola.audit.AuditSupport.*
import versola.audit.AuthAuditDetails
import versola.audit.{AuthActors, AuthAuditKinds}
import versola.oauth.client.model.{ClientCredentials, OAuthClientRecord}
import versola.oauth.clientauth.{AuthenticatedEndpoint, ClientAuthentication}
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.mtls.ClientCertificate
import versola.oauth.revocation.{RevocationCause, RevocationTerms}
import versola.oauth.revoke.model.RevocationError
import versola.oauth.session.SessionRepository
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.{Base64, CoreConfig, Secret, SecurityService}
import zio.{Clock, IO, Task, ZIO, ZLayer}

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

    private def audited(client: OAuthClientRecord, userId: Option[UUID], details: AuthAuditDetails.TokenRevoked): IO[Throwable, Unit] =
      audit.log(
        AuditDraft(
          kind = AuthAuditKinds.TokenRevoked,
          outcome = AuditOutcome.success,
          reason = None,
          actor = Some((AuthActors.client, Some(client.id))),
          userId = userId,
          target = None,
          details = details,
        ),
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
            // No access token was presented here, and none is recorded against the chain, so the
            // revocation names the family every token this grant issued carries. The whole family
            // goes, retired generations included, not only the presented row: any of them could be
            // the one in the wrong hands. The bound is the client's current `accessTokenTtl` from
            // now: an approximation, and the only one available once the tokens themselves are not
            // being named. The revocation is recorded in the same transaction as the expiry; the
            // push to the client's back channel follows.
            Clock.instant.flatMap: revokedAt =>
              sessionRepository.deleteByFamily(record.familyId, RevocationTerms.forFamily(client, RevocationCause.Revoke)) *>
                accessTokenRevocationService.announceFamily(
                  client = client,
                  family = record.familyId,
                  subject = record.userId.toString,
                  expiresAt = revokedAt.plus(client.accessTokenTtl),
                ) *> audited(
                  client,
                  Some(record.userId),
                  AuthAuditDetails.TokenRevoked(
                    tokenType = "refresh_token",
                    jti = None,
                    refreshPrefix = Some(Base64.urlEncode(token).take(9)),
                    cause = "rfc7009",
                    count = None,
                  ),
                )
      yield ()

    override def revokeAccessToken(
        token: AccessTokenPayload,
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
    ): IO[Throwable | RevocationError, Unit] =
      for
        client <- authenticateClient(credentials, certificate)
        // The owner is compared as a whole id, not as a substring of one.
        _ <- ZIO.fail(RevocationError.InvalidClient)
          .when(token.clientId != client.id)

        // The token was presented and parsed, so its own `exp` is exact: recorded for exactly that
        // long, and pushed to the client's back channel.
        _ <- accessTokenRevocationService.revoke(client, token)
        _ <- audited(
          client,
          Try(UUID.fromString(token.subject)).toOption,
          AuthAuditDetails.TokenRevoked(
            tokenType = "access_token",
            jti = Some(Base64.urlEncode(token.id)),
            refreshPrefix = None,
            cause = "rfc7009",
            count = None,
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

package versola.oauth.introspect

import versola.oauth.client.model.{AuthorizationDetail, ClientCredentials, OAuthClientRecord, ResourceRecord, ResourceUri}
import versola.oauth.client.{OAuthConfigurationService, ResourceResolver}
import versola.oauth.clientauth.{AuthenticatedEndpoint, ClientAuthentication}
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.mtls.ClientCertificate
import versola.oauth.revoke.AccessTokenRevocationService
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.RefreshTokenRecord
import versola.user.model.UserId
import versola.user.move.{DepartureRefusal, UserDepartures}
import versola.util.{CoreConfig, Secret, SecurityService}
import zio.json.ast.Json
import zio.{IO, UIO, ZIO, ZLayer}

import java.util.UUID
import scala.util.Try

trait IntrospectionService:
  def introspectAccessToken(
      token: AccessTokenPayload,
      credentials: ClientCredentials,
      certificate: Option[ClientCertificate],
  ): IO[Throwable | IntrospectionError, IntrospectionResponse]

  def introspectRefreshToken(
      token: RefreshToken,
      credentials: ClientCredentials,
      certificate: Option[ClientCertificate],
  ): IO[Throwable | IntrospectionError, IntrospectionResponse]

object IntrospectionService:
  def live: ZLayer[
    OAuthConfigurationService & ClientAuthentication & SessionRepository & SecurityService & CoreConfig &
      AccessTokenRevocationService & UserDepartures,
    Nothing,
    IntrospectionService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))

  class Impl(
      oauthClientService: OAuthConfigurationService,
      clientAuthentication: ClientAuthentication,
      sessionRepository: SessionRepository,
      securityService: SecurityService,
      config: CoreConfig,
      accessTokenRevocation: AccessTokenRevocationService,
      departures: UserDepartures,
  ) extends IntrospectionService:

    private def toIntrospectionError(error: Throwable | DepartureRefusal): Throwable | IntrospectionError = error match
      case DepartureRefusal.Leaving => IntrospectionError.Unavailable
      case DepartureRefusal.Moved(cell) => IntrospectionError.Moved(cell)
      case other: Throwable => other

    override def introspectAccessToken(
        token: AccessTokenPayload,
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
    ): IO[Throwable | IntrospectionError, IntrospectionResponse] =
      for
        requester <- authenticateClient(credentials, certificate)
        audience <- resolveAudience(token, requester)
        _ <- ZIO.fail(IntrospectionError.Unauthenticated).when(audience.isEmpty)
        // RFC 7662 §2.2: a revoked token is `active: false`, with nothing to say why. After the
        // caller's right to ask about this token is established, not before: otherwise a caller
        // with no such right could tell a revoked token from a live one by the difference in
        // answer. Answered from the replica's in-memory list, no query. Before the departure is looked at: a token this cell
        // knows to be revoked is inactive whatever became of its user, and is not pointed at another cell (versolauth/versola#1051).
        revoked <- accessTokenRevocation.isRevoked(token)
        // A live token of a user who is leaving, or has gone, after the same right is established.
        _ <- ZIO.foreachDiscard(Try(UUID.fromString(token.subject)).toOption)(id =>
          departures.refuse(UserId(id)).mapError(toIntrospectionError),
        ).unless(revoked)
      yield if revoked then IntrospectionResponse.Inactive else buildJwtIntrospectionResponse(token, audience)

    private def resolveAudience(
        token: AccessTokenPayload,
        requester: OAuthClientRecord,
    ): UIO[Vector[ResourceUri]] =
      for
        issuerResources <- oauthClientService.getResourcesForClient(requester.tenantId, token.clientId)
        requesterResources <- oauthClientService.getResourcesForClient(requester.tenantId, requester.id)
      yield
        val availableResources = requesterResources.map(toAudience).toSet
        val issuerInternalResources = issuerResources.filter(_.internal).map(toAudience)

        token.audience.flatMap:
          case ResourceResolver.EdgeResource =>
            issuerInternalResources.filter(availableResources.contains)
          case resource if availableResources.contains(resource) =>
            List(resource)
          case _ =>
            Nil
        .distinct

    private def toAudience(resource: ResourceRecord): ResourceUri =
      if resource.internal then
        ResourceUri(s"resource://${resource.resourceId}")
      else
        resource.resource

    private def buildJwtIntrospectionResponse(
        token: AccessTokenPayload,
        audience: Vector[ResourceUri],
    ): IntrospectionResponse =
      IntrospectionResponse(
        active = true,
        clientId = Some(token.clientId),
        scope = Some(token.scope.mkString(" ")),
        username = None,
        // RFC 9449 §6.2: a DPoP-bound token introspects as such, echoing its confirmation.
        // A certificate-bound one stays `Bearer` -- RFC 8705 §3.1 binds the token without
        // changing how it is presented -- and says so through `cnf` alone.
        tokenType = Some(if token.confirmation.exists(_.jkt.isDefined) then "DPoP" else "Bearer"),
        cnf = token.confirmation.map(_.toJsonObj),
        exp = Some(token.expiresAt.getEpochSecond),
        iat = Some(token.issuedAt.getEpochSecond),
        nbf = token.notBefore.map(_.getEpochSecond),
        sub = Some(token.subject),
        aud = Some(audience),
        iss = Some(token.issuer),
        jti = Some(token.id.encoded),
        authorizationDetails = authorizationDetailsOf(token.authorizationDetails.getOrElse(Nil)),
      )

    private def authorizationDetailsOf(details: List[AuthorizationDetail]): Option[Json.Arr] =
      Option.when(details.nonEmpty)(Json.Arr(details.map(_.value)*))

    override def introspectRefreshToken(
        token: RefreshToken,
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
    ): IO[Throwable | IntrospectionError, IntrospectionResponse] =
      for
        client <- authenticateClient(credentials, certificate)
        tokenMac <- securityService.mac(Secret(token), config.security.refreshTokensSecret)
        tokenRecord <- sessionRepository.findToken(tokenMac)

        _ <- ZIO.fail(IntrospectionError.Unauthenticated)
          .when(tokenRecord.exists(_.clientId != client.id))
        _ <- tokenRecord match
          case Some(record) => departures.refuse(record.userId).mapError(toIntrospectionError)
          case None => departures.refuseToken(tokenMac, client.id).mapError(toIntrospectionError)
      yield buildIntrospectionResponse(tokenRecord)

    /** RFC 7662 requires the introspection endpoint to authenticate the caller, and a bare
      * `client_id` would let anyone who merely knows a public client's id introspect tokens
      * for that client's audience -- hence `secretRequired`. RFC 8705 §2.1: a client that
      * registered an mTLS subject holds no secret and authenticates with its certificate. */
    private def authenticateClient(
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
    ): IO[Throwable | IntrospectionError, OAuthClientRecord] =
      clientAuthentication.authenticate(
        credentials = credentials,
        certificate = certificate,
        endpoint = AuthenticatedEndpoint.Introspection,
        secretRequired = true,
      ).mapError {
        case error: Throwable => error
        case _ => IntrospectionError.InvalidClient
      }

    private def buildIntrospectionResponse(record: Option[RefreshTokenRecord]): IntrospectionResponse =
      record match
        case Some(record) =>
          IntrospectionResponse(
            active = true,
            scope = Some(record.scope.mkString(" ")),
            clientId = Some(record.clientId),
            sub = Some(record.userId.toString),
            // RFC 9449 §6.2: same as above, for a refresh-token-derived introspection.
            tokenType = Some(if record.cnf.exists(_.jkt.isDefined) then "DPoP" else "Bearer"),
            cnf = record.cnf.map(_.toJsonObj),
            username = None,
            exp = Some(record.expiresAt.getEpochSecond),
            nbf = None,
            iss = Some(config.jwt.issuer),
            iat = Some(record.issuedAt.getEpochSecond),
            aud = Some(record.audience.toVector),
            jti = None,
            authorizationDetails = authorizationDetailsOf(record.authorizationDetails.getOrElse(Nil)),
          )
        case None =>
          IntrospectionResponse.Inactive

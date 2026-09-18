package versola.oauth.introspect

import versola.oauth.client.{OAuthConfigurationService, ResourceResolver}
import versola.oauth.client.model.{AuthorizationDetail, ClientCredentials, OAuthClientRecord, ResourceRecord, ResourceUri}
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.clientauth.{AuthenticatedEndpoint, ClientAuthentication}
import versola.oauth.mtls.ClientCertificate
import versola.oauth.model.{AccessTokenPayload, RefreshToken}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.RefreshTokenRecord
import versola.util.{CoreConfig, Secret, SecurityService}
import zio.json.ast.Json
import zio.{IO, UIO, ZIO, ZLayer}

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
    OAuthConfigurationService & ClientAuthentication & SessionRepository & SecurityService & CoreConfig,
    Nothing,
    IntrospectionService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      oauthClientService: OAuthConfigurationService,
      clientAuthentication: ClientAuthentication,
      sessionRepository: SessionRepository,
      securityService: SecurityService,
      config: CoreConfig,
  ) extends IntrospectionService:

    override def introspectAccessToken(
        token: AccessTokenPayload,
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
    ): IO[Throwable | IntrospectionError, IntrospectionResponse] =
      for
        requester <- authenticateClient(credentials, certificate)
        audience <- resolveAudience(token, requester)
        _ <- ZIO.fail(IntrospectionError.Unauthenticated).when(audience.isEmpty)
      yield buildJwtIntrospectionResponse(token, audience)

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

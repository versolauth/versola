package versola.oauth.introspect

import com.nimbusds.jose.crypto.{ECDSAVerifier, MACVerifier, RSASSAVerifier}
import com.nimbusds.jose.jwk.{ECKey, OctetSequenceKey, RSAKey}
import com.nimbusds.jwt.SignedJWT
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.OAuthClientRecord
import versola.oauth.introspect.model.{IntrospectionError, IntrospectionResponse}
import versola.oauth.session.TokenRepository
import versola.oauth.session.model.TokenRecord
import versola.util.http.{ClientCredentials, ClientIdWithSecret}
import versola.util.{CoreConfig, Secret, SecurityService}
import zio.{IO, ZIO, ZLayer}

import scala.jdk.CollectionConverters.*

trait IntrospectionService:
  def introspectJWTAccessToken(
      token: SignedJWT,
      credentials: ClientCredentials,
  ): IO[IntrospectionError, IntrospectionResponse]

  def introspectOpaqueAccessToken(
      token: AccessToken,
      credentials: ClientCredentials,
  ): IO[Throwable | IntrospectionError, IntrospectionResponse]

  def introspectRefreshToken(
      token: RefreshToken,
      credentials: ClientCredentials,
  ): IO[Throwable | IntrospectionError, IntrospectionResponse]

object IntrospectionService:
  def live: ZLayer[
    OAuthClientService & TokenRepository & SecurityService & CoreConfig,
    Nothing,
    IntrospectionService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      oauthClientService: OAuthClientService,
      tokenRepository: TokenRepository,
      securityService: SecurityService,
      config: CoreConfig,
  ) extends IntrospectionService:

    override def introspectJWTAccessToken(
        token: SignedJWT,
        credentials: ClientCredentials,
    ): IO[IntrospectionError, IntrospectionResponse] =
      for
        _ <- authenticateClient(credentials)
        response <- ZIO.succeed:
          if !validSignature(token) then IntrospectionResponse.Inactive
          else buildJwtIntrospectionResponse(token)
      yield response

    private def buildJwtIntrospectionResponse(token: SignedJWT): IntrospectionResponse =
      val claims = token.getJWTClaimsSet
      val now = java.time.Instant.now()
      val expiration = Option(claims.getExpirationTime).map(_.toInstant)

      // Check if token is expired
      if expiration.exists(_.isBefore(now)) then IntrospectionResponse.Inactive
      else
        IntrospectionResponse(
          active = true,
          clientId = None, // JWT doesn't contain client_id in current implementation
          scope = None, // JWT doesn't contain scope in current implementation
          username = None,
          tokenType = Some("Bearer"),
          exp = expiration.map(_.getEpochSecond),
          iat = Option(claims.getIssueTime).map(_.toInstant.getEpochSecond),
          nbf = Option(claims.getNotBeforeTime).map(_.toInstant.getEpochSecond),
          sub = Option(claims.getSubject),
          aud = Option(claims.getAudience).flatMap(_.asScala.headOption),
          iss = Option(claims.getIssuer),
          jti = Option(claims.getJWTID),
        )

    private def validSignature(token: SignedJWT): Boolean =
      Option(config.jwt.jwkSet.getKeyByKeyId(token.getHeader.getKeyID))
        .exists:
          case key: RSAKey => token.verify(RSASSAVerifier(key))
          case key: ECKey => token.verify(ECDSAVerifier(key))
          case key: OctetSequenceKey => token.verify(MACVerifier(key))
          case _ => false


    override def introspectOpaqueAccessToken(
        token: AccessToken,
        credentials: ClientCredentials,
    ): IO[Throwable | IntrospectionError, IntrospectionResponse] =
      for
        _ <- authenticateClient(credentials)
        tokenMac <- securityService.macBlake3(Secret(token), config.security.accessTokens.pepper)
        tokenRecord <- tokenRepository.findAccessToken(tokenMac)
      yield buildIntrospectionResponse(tokenRecord)

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
            sub = Some(record.userId.toString),
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

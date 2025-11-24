package versola.auth

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.apache.commons.codec.digest.Blake3
import versola.OAuthApp
import versola.auth.model.{AccessToken, DeviceId, RefreshToken, TokenType}
import versola.oauth.conversation.model.AuthId
import versola.security.{MAC, Secret, SecureRandom, SecurityService}
import versola.user.model.{UserId, UserRecord}
import versola.util.CoreConfig
import zio.*
import zio.json.*

import java.time.Instant
import java.util.{Base64, Date}

case class IssuedTokens()

trait TokenService:
  def issueTokens(
      userId: UserRecord,
      authId: AuthId
  ): Task[IssuedTokens]

  def reissueTokens(
      refreshToken: RefreshToken,
      deviceId: DeviceId,
  ): IO[Throwable | Unit, IssuedTokens]

object TokenService:
  def live = ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      secureRandom: SecureRandom,
      securityService: SecurityService,
      config: CoreConfig,
  ) extends TokenService:
    private val encoder = Base64.getUrlEncoder.withoutPadding()

    override def issueTokens(
        user: UserRecord,
        authId: AuthId,
    ): Task[IssuedTokens] =
      for
        now <- Clock.instant

        accessToken <- createAccessToken(user.id, authId, now)
        (refreshToken, mac) <- createRefreshToken

      yield IssuedTokens(
      )

    private def createRefreshToken: Task[(RefreshToken, MAC)] =
      for
        bytes <- secureRandom.nextBytes(64)
        mac <- securityService.macBlake3(Secret(bytes), config.security.refreshTokens.pepper)
      yield (RefreshToken(bytes), mac)

    private def createAccessToken(
        userId: UserId,
        authId: AuthId,
        now: Instant,
    ): Task[AccessToken] =
      for
        id <- secureRandom.nextHex(16)
        claims =
          JWTClaimsSet.Builder()
            .issuer(config.jwt.issuer)
            .subject(userId.toString)
            .audience("???")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(TokenType.AccessToken.ttl.toSeconds)))
            .jwtID(id)
            .claim("auth_id", authId)
            .build()

        jwt <- ZIO.attemptBlocking:
          val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .`type`(JOSEObjectType(TokenType.AccessToken.typ))
            .keyID(config.jwt.jwkSet.getKeys.get(0).getKeyID)
            .build()
          val jwt = SignedJWT(header, claims)
          val signer = RSASSASigner(config.jwt.privateKey)
          jwt.sign(signer)
          jwt.serialize()
      yield AccessToken(jwt)

    override def reissueTokens(
        refreshToken: RefreshToken,
        deviceId: DeviceId,
    ): IO[Throwable | Unit, IssuedTokens] =
      for
        now <- Clock.instant
        mac <- securityService.macBlake3(Secret(refreshToken), config.security.refreshTokens.pepper)
      yield ???

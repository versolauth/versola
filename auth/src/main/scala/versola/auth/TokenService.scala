package versola.auth

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.apache.commons.codec.digest.Blake3
import versola.AuthServer
import versola.auth.model.{AccessToken, AuthId, DeviceId, IssuedTokens, RefreshToken, TokenType, UserDeviceRecord}
import versola.security.{MAC, Secret, SecureRandom, SecurityService}
import versola.user.model.{UserId, UserRecord, UserResponse}
import versola.util.CoreConfig
import zio.*
import zio.json.*

import java.time.Instant
import java.util.{Base64, Date}

trait TokenService:
  def issueTokens(
      userId: UserRecord,
      authId: AuthId,
      providedDeviceId: Option[DeviceId],
  ): Task[IssuedTokens]

  def reissueTokens(
      refreshToken: RefreshToken,
      deviceId: DeviceId,
  ): IO[Throwable | Unit, IssuedTokens]

  def logout(
      userId: UserId,
      deviceId: DeviceId,
  ): Task[Unit]

object TokenService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      userDevicesRepository: UserDeviceRepository,
      secureRandom: SecureRandom,
      securityService: SecurityService,
      jwtConfig: CoreConfig.JwtConfig,
      refreshTokensConfig: CoreConfig.Security.RefreshTokens
  ) extends TokenService:
    private val encoder = Base64.getUrlEncoder.withoutPadding()

    override def issueTokens(
        user: UserRecord,
        authId: AuthId,
        providedDeviceId: Option[DeviceId],
    ): Task[IssuedTokens] =
      for
        now <- Clock.instant
        deviceId <- ZIO.fromOption(providedDeviceId)
          .orElse(secureRandom.nextUUIDv7.map(DeviceId(_)))

        accessToken <- createAccessToken(user.id, authId, deviceId, now)
        (refreshToken, mac) <- createRefreshToken

        _ <- userDevicesRepository.overwrite(
          UserDeviceRecord(
            userId = user.id,
            deviceId = deviceId,
            authId = authId,
            refreshTokenBlake3 = Some(RefreshToken.fromBytes(mac)),
            expireAt = Some(now.plusSeconds(TokenType.RefreshToken.ttl.toSeconds)),
          ),
        )
      yield IssuedTokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
        deviceId = Option.when(providedDeviceId.isEmpty)(deviceId),
        user = Some(UserResponse.from(user)),
      )

    private def createRefreshToken: Task[(RefreshToken, MAC)] =
      for
        bytes <- secureRandom.nextBytes(64)
        mac <- securityService.macBlake3(Secret(bytes), refreshTokensConfig.pepper)
      yield (RefreshToken.fromBytes(bytes), mac)

    private def createAccessToken(
        userId: UserId,
        authId: AuthId,
        deviceId: DeviceId,
        now: Instant,
    ): Task[AccessToken] =
      for
        id <- secureRandom.nextHex(16)
        claims =
          JWTClaimsSet.Builder()
            .issuer("???")
            .subject(userId.toString)
            .audience("???")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(TokenType.AccessToken.ttl.toSeconds)))
            .jwtID(id)
            .claim("auth_id", authId)
            .claim("device_id", deviceId)
            .build()

        jwt <- ZIO.attemptBlocking:
          val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .`type`(JOSEObjectType(TokenType.AccessToken.typ))
            .keyID(jwtConfig.jwkSet.getKeys.get(0).getKeyID)
            .build()
          val jwt = SignedJWT(header, claims)
          val signer = RSASSASigner(jwtConfig.privateKey)
          jwt.sign(signer)
          jwt.serialize()
      yield AccessToken(jwt)

    override def reissueTokens(
        refreshToken: RefreshToken,
        deviceId: DeviceId,
    ): IO[Throwable | Unit, IssuedTokens] =
      for
        now <- Clock.instant
        mac <- securityService.macBlake3(Secret.fromBase64Url(refreshToken), refreshTokensConfig.pepper)
        record <- userDevicesRepository.findByRefreshToken(mac).someOrFail(())

        _ <- (userDevicesRepository.clearRefreshByUserIdAndDeviceId(record.userId, record.deviceId) *> ZIO.fail(()))
          .when(record.expireAt.forall(_.isBefore(now)) || record.deviceId != deviceId)

        accessToken <- createAccessToken(
          userId = record.userId,
          authId = record.authId,
          deviceId = record.deviceId,
          now = now,
        )

        (newRefreshToken, newMAC) <- createRefreshToken

        _ <- userDevicesRepository.update(
          oldRefreshToken = mac,
          newRefreshToken = newMAC,
          expireAt = now.plusSeconds(TokenType.RefreshToken.ttl.toSeconds),
        )
      yield IssuedTokens(
        accessToken = accessToken,
        refreshToken = newRefreshToken,
        deviceId = None,
        user = None,
      )

    override def logout(
        userId: UserId,
        deviceId: DeviceId,
    ): Task[Unit] =
      userDevicesRepository.clearRefreshByUserIdAndDeviceId(userId, deviceId)

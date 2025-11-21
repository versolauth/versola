package versola.util

import com.nimbusds.jwt.JWTClaimsSet
import versola.auth.model.DeviceId
import versola.http.JWTClaimDecoder
import versola.user.model.UserId
import zio.{IO, ZIO}

import java.util.UUID

object JWTDecoders:

  given JWTClaimDecoder[(userId: UserId, deviceId: DeviceId)] = new:
    override def decode(claims: JWTClaimsSet): IO[String, (userId: UserId, deviceId: DeviceId)] =
      for
        userId <- ZIO.attempt(UserId(UUID.fromString(claims.getSubject)))
          .orElseFail("claim 'sub' is missing or not a valid UUID")
        deviceId <- ZIO.attempt(DeviceId(UUID.fromString(claims.getStringClaim("device_id"))))
          .orElseFail("claim 'device_id' is missing or not a valid UUID")
      yield (userId = userId, deviceId = deviceId)

    override def attributes(claims: JWTClaimsSet): IO[String, Map[String, String]] =
      ZIO.attempt(
        Map(
          "userId" -> claims.getStringClaim("sub"),
          "authId" -> claims.getStringClaim("auth_id"),
          "deviceId" -> claims.getStringClaim("device_id"),
          "tokenId" -> claims.getJWTID
        ),
      ).orElseFail("claim 'auth_id' or 'device_id' is invalid")


  given JWTClaimDecoder[UserId] = given_JWTClaimDecoder_UserId_DeviceId.map(_.userId)
  
  given JWTClaimDecoder[Unit] = given_JWTClaimDecoder_UserId_DeviceId.map(_ => ())

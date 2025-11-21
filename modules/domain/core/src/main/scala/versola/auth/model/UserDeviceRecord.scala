package versola.auth.model

import versola.user.model.UserId

import java.time.Instant

case class UserDeviceRecord(
    userId: UserId,
    deviceId: DeviceId,
    authId: AuthId,
    refreshTokenBlake3: Option[String],
    expireAt: Option[Instant],
)

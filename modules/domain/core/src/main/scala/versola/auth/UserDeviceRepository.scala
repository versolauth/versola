package versola.auth

import versola.auth.model.{DeviceId, UserDeviceRecord}
import versola.security.MAC
import versola.user.model.UserId
import zio.Task

import java.time.Instant

trait UserDeviceRepository:
  def update(
      oldRefreshToken: MAC,
      newRefreshToken: MAC,
      expireAt: Instant,
  ): Task[Unit]

  def overwrite(record: UserDeviceRecord): Task[Unit]

  def findByRefreshToken(refreshToken: MAC): Task[Option[UserDeviceRecord]]

  def listByUserId(userId: UserId): Task[Vector[UserDeviceRecord]]

  def clearRefreshByUserIdAndDeviceId(userId: UserId, deviceId: DeviceId): Task[Unit]

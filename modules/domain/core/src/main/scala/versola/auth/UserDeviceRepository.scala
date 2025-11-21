package versola.auth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AuthId, DeviceId, RefreshToken, UserDeviceRecord}
import versola.user.model.UserId
import zio.Task

import java.time.Instant
import java.util.UUID

trait UserDeviceRepository:
  def update(
      oldBlake3Hash: String,
      newBlake3Hash: String,
      expireAt: Instant,
  ): Task[Unit]

  def overwrite(record: UserDeviceRecord): Task[Unit]

  def findByRefreshTokenHash(blake3Hash: String): Task[Option[UserDeviceRecord]]

  def listByUserId(userId: UserId): Task[Vector[UserDeviceRecord]]

  def clearRefreshByUserIdAndDeviceId(userId: UserId, deviceId: DeviceId): Task[Unit]


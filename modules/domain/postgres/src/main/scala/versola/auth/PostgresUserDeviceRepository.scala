package versola.auth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AuthId, DeviceId, RefreshToken, UserDeviceRecord}
import versola.security.MAC
import versola.user.model.UserId
import zio.Task

import java.time.Instant
import java.util.UUID

class PostgresUserDeviceRepository(xa: TransactorZIO) extends UserDeviceRepository:

  override def update(
      oldRefreshToken: MAC,
      newRefreshToken: MAC,
      expireAt: Instant,
  ): Task[Unit] =
    val oldBlake3Hash = RefreshToken.fromBytes(oldRefreshToken)
    val newBlake3Hash = RefreshToken.fromBytes(newRefreshToken)
    xa.connect:
      sql"""update user_devices
            set refresh_token_blake3 = $newBlake3Hash, expire_at = $expireAt
            where refresh_token_blake3 = $oldBlake3Hash
       """.update.run()

  override def overwrite(record: UserDeviceRecord): Task[Unit] =
    xa.connect:
      sql"""insert into user_devices (user_id, device_id, auth_id, refresh_token_blake3, expire_at)
            values (${record.userId}, ${record.deviceId}, ${record.authId}, ${record.refreshTokenBlake3}, ${record.expireAt})
            on conflict (user_id, device_id) do update set
              auth_id = excluded.auth_id,
              refresh_token_blake3 = excluded.refresh_token_blake3,
              expire_at = excluded.expire_at
       """.update.run()

  override def findByRefreshToken(refreshToken: MAC): Task[Option[UserDeviceRecord]] =
    val blake3Hash = RefreshToken.fromBytes(refreshToken)
    xa.connect:
      sql"""
           select * from user_devices where refresh_token_blake3 = $blake3Hash
         """
        .query[UserDeviceRecord]
        .run()
        .headOption

  override def clearRefreshByUserIdAndDeviceId(userId: UserId, deviceId: DeviceId): Task[Unit] =
    xa.connect:
      sql"""update user_devices
            set refresh_token_blake3 = null, expire_at = null
            where user_id = $userId and device_id = $deviceId
       """.update.run()

  override def listByUserId(userId: UserId): Task[Vector[UserDeviceRecord]] =
    xa.connect:
      sql"""select user_id, device_id, auth_id, refresh_token_blake3, expire_at
                from user_devices
                where user_id = $userId"""
        .query[UserDeviceRecord]
        .run()

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[DeviceId] = DbCodec.UUIDCodec.biMap(DeviceId(_), identity[UUID])
  given DbCodec[AuthId] = DbCodec.UUIDCodec.biMap(AuthId(_), identity[UUID])
  given DbCodec[RefreshToken] = DbCodec.StringCodec.biMap(RefreshToken(_), identity[String])
  given DbCodec[UserDeviceRecord] = DbCodec.derived[UserDeviceRecord]

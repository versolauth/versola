package versola.auth

object PostgresPhoneVerificationsRepository
/*
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AuthId, DeviceId, OtpCode, PhoneVerificationRecord}
import versola.util.Phone
import zio.Task

import java.time.Instant
import java.util.UUID

class PostgresPhoneVerificationsRepository(xa: TransactorZIO) extends PhoneVerificationsRepository:

  override def find(phone: Phone): Task[Option[PhoneVerificationRecord]] =
    xa.connect:
      findQuery(phone).run().headOption

  override def findByAuthId(authId: AuthId): Task[Option[PhoneVerificationRecord]] =
    xa.connect:
      findByAuthIdQuery(authId).run().headOption

  private def findQuery(phone: Phone) =
    sql"""select phone, auth_id, device_id, code, times_sent
                from phone_verifications
                where phone = $phone"""
      .query[PhoneVerificationRecord]

  private def findByAuthIdQuery(authId: AuthId) =
    sql"""select phone, auth_id, device_id, code, times_sent
                from phone_verifications
                where auth_id = $authId"""
      .query[PhoneVerificationRecord]

  override def update(phone: Phone, code: OtpCode, timesSent: Int): Task[Unit] =
    xa.connect:
      sql"""update phone_verifications
            set code = $code, times_sent = $timesSent
            where phone = $phone"""
        .update
        .run()

  override def overwrite(record: PhoneVerificationRecord): Task[Unit] =
    xa.connect:
      sql"""insert into phone_verifications (phone, auth_id, device_id, code, times_sent)
            values (${record.phone}, ${record.authId}, ${record.deviceId}, ${record.code}, ${record.timesSent})
            on conflict (phone) do update set
              auth_id = excluded.auth_id,
              device_id = excluded.device_id,
              code = excluded.code,
              times_sent = excluded.times_sent
       """.update.run()

  override def create(record: PhoneVerificationRecord): Task[Option[PhoneVerificationRecord]] = {
    xa.connect:
      try
        insertQuery(record).run()
        None
      catch
        case ex: SqlException if ex.getMessage.contains("phone_verifications_pkey") =>
          findQuery(record.phone).run().headOption
  }

  private def insertQuery(record: PhoneVerificationRecord) =
    sql"""
     insert into phone_verifications (phone, auth_id, device_id, code, times_sent)
     values (${record.phone}, ${record.authId}, ${record.deviceId}, ${record.code}, ${record.timesSent})
       """.update

  override def delete(phone: Phone): Task[Unit] =
    xa.connect:
      sql"""delete from phone_verifications where phone = $phone"""
        .update
        .run()

  given DbCodec[AuthId] = DbCodec.UUIDCodec.biMap(AuthId(_), identity[UUID])
  given DbCodec[DeviceId] = DbCodec.UUIDCodec.biMap(DeviceId(_), identity[UUID])
  given DbCodec[OtpCode] = DbCodec.StringCodec.biMap(OtpCode(_), identity[String])
  given DbCodec[Phone] = DbCodec.StringCodec.biMap(Phone(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[PhoneVerificationRecord] = DbCodec.derived[PhoneVerificationRecord]

*/

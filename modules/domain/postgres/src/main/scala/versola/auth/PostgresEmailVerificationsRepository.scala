package versola.auth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AuthId, DeviceId, EmailVerificationRecord, OtpCode}
import versola.user.model.Email
import zio.Task

import java.time.Instant
import java.util.UUID

class PostgresEmailVerificationsRepository(xa: TransactorZIO) extends EmailVerificationsRepository:

  override def find(email: Email): Task[Option[EmailVerificationRecord]] =
    xa.connect:
      findQuery(email).run().headOption

  override def findByAuthId(authId: AuthId): Task[Option[EmailVerificationRecord]] =
    xa.connect:
      findByAuthIdQuery(authId).run().headOption

  private def findQuery(email: Email) =
    sql"""select email, auth_id, device_id, code, times_sent
                from email_verifications
                where email = $email"""
      .query[EmailVerificationRecord]

  private def findByAuthIdQuery(authId: AuthId) =
    sql"""select email, auth_id, device_id, code, times_sent
                from email_verifications
                where auth_id = $authId"""
      .query[EmailVerificationRecord]

  override def update(email: Email, code: OtpCode, timesSent: Int): Task[Unit] =
    xa.connect:
      sql"""update email_verifications
            set code = $code, times_sent = $timesSent
            where email = $email"""
        .update
        .run()

  override def overwrite(record: EmailVerificationRecord): Task[Unit] =
    xa.connect:
      sql"""insert into email_verifications (email, auth_id, device_id, code, times_sent)
            values (${record.email}, ${record.authId}, ${record.deviceId}, ${record.code}, ${record.timesSent})
            on conflict (email) do update set
              auth_id = excluded.auth_id,
              device_id = excluded.device_id,
              code = excluded.code,
              times_sent = excluded.times_sent
       """.update.run()

  override def create(record: EmailVerificationRecord): Task[Option[EmailVerificationRecord]] = {
    xa.connect:
      try
        insertQuery(record).run()
        None
      catch
        case ex: SqlException if ex.getMessage.contains("email_verifications_pkey") =>
          findQuery(record.email).run().headOption
  }

  private def insertQuery(record: EmailVerificationRecord) =
    sql"""
     insert into email_verifications (email, auth_id, device_id, code, times_sent)
     values (${record.email}, ${record.authId}, ${record.deviceId}, ${record.code}, ${record.timesSent})
       """.update

  override def delete(email: Email): Task[Unit] =
    xa.connect:
      sql"""delete from email_verifications where email = $email"""
        .update
        .run()

  given DbCodec[AuthId] = DbCodec.UUIDCodec.biMap(AuthId(_), identity[UUID])
  given DbCodec[DeviceId] = DbCodec.UUIDCodec.biMap(DeviceId(_), identity[UUID])
  given DbCodec[OtpCode] = DbCodec.StringCodec.biMap(OtpCode(_), identity[String])
  given DbCodec[Email] = DbCodec.StringCodec.biMap(Email(_), identity[String])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[EmailVerificationRecord] = DbCodec.derived[EmailVerificationRecord]

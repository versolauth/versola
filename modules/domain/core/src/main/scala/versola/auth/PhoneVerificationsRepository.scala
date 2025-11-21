package versola.auth

import versola.auth.model.{AuthId, OtpCode, PhoneVerificationRecord}
import versola.util.Phone
import zio.Task

trait PhoneVerificationsRepository:
  def find(phone: Phone): Task[Option[PhoneVerificationRecord]]

  def findByAuthId(authId: AuthId): Task[Option[PhoneVerificationRecord]]

  /* Возвращает запись в случае гонки, если другая запись уже существует */
  def create(record: PhoneVerificationRecord): Task[Option[PhoneVerificationRecord]]

  def overwrite(record: PhoneVerificationRecord): Task[Unit]

  def update(phone: Phone, code: OtpCode, timesSent: Int): Task[Unit]

  def delete(phone: Phone): Task[Unit]


package versola.auth

import versola.auth.model.{AuthId, EmailVerificationRecord, OtpCode}
import versola.user.model.Email
import zio.Task

trait EmailVerificationsRepository:
  def find(email: Email): Task[Option[EmailVerificationRecord]]

  def findByAuthId(authId: AuthId): Task[Option[EmailVerificationRecord]]

  /* Возвращает запись в случае гонки, если другая запись уже существует */
  def create(record: EmailVerificationRecord): Task[Option[EmailVerificationRecord]]

  def overwrite(record: EmailVerificationRecord): Task[Unit]

  def update(email: Email, code: OtpCode, timesSent: Int): Task[Unit]

  def delete(email: Email): Task[Unit]

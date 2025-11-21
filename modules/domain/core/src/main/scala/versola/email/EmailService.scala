package versola.email

import versola.auth.model.OtpCode
import versola.email.model.{EmailMessage, EmailTemplate}
import versola.user.model.Email
import zio.*

trait EmailService:
  def sendEmail(message: EmailMessage): Task[Unit]
  
  def sendVerificationEmail(email: Email, code: OtpCode): Task[Unit] =
    sendEmail(EmailTemplate.verificationCode(email, code))

object EmailService:
  def sendEmail(message: EmailMessage): ZIO[EmailService, Throwable, Unit] =
    ZIO.serviceWithZIO[EmailService](_.sendEmail(message))
    
  def sendVerificationEmail(email: Email, code: OtpCode): ZIO[EmailService, Throwable, Unit] =
    ZIO.serviceWithZIO[EmailService](_.sendVerificationEmail(email, code))

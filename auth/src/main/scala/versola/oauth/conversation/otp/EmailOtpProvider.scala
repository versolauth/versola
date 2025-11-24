package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.util.{CoreConfig, Email}
import zio.{Task, ZIO, ZLayer}

trait EmailOtpProvider:
  def sendOtp(
      email: Email,
      code: OtpCode,
      template: OtpTemplate,
  ): Task[Unit]

object EmailOtpProvider:
  val live = ZLayer.succeed(SMTPOtpProvider())

class SMTPOtpProvider() extends EmailOtpProvider:
  override def sendOtp(
      email: Email,
      code: OtpCode,
      template: OtpTemplate,
  ): Task[Unit] = ZIO.unit

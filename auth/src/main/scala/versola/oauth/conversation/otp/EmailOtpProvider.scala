package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.delivery.{ChannelUnavailable, DeliveryChannels}
import versola.util.Email
import zio.{Task, URLayer, ZIO, ZLayer}

trait EmailOtpProvider:
  def sendOtp(
      email: Email,
      code: OtpCode,
      template: OtpTemplate,
  ): Task[Unit]

  /** Fails with [[ChannelUnavailable]] when no SMTP server is configured and switched on, unlike [[sendOtp]], which
    * logs and goes on: a one-time code that does not arrive is asked for again, a temporary password that does not
    * arrive is a locked-out user, so the caller that stored one has to be told. */
  def send(email: Email, message: String): Task[Unit]

object EmailOtpProvider:
  val live: URLayer[DeliveryChannels, EmailOtpProvider] =
    ZLayer.fromFunction(Impl(_))

  /** Sends through whatever SMTP server is configured when the message goes out: the one saved in central or the `smtp`
    * block of `auth.conf` (see [[DeliveryChannels]]), so a change applies without a restart. Nothing is sent, and the
    * reason is logged, when neither exists or the saved one is switched off.
    */
  class Impl(channels: DeliveryChannels) extends EmailOtpProvider:
    override def sendOtp(email: Email, code: OtpCode, template: OtpTemplate): Task[Unit] =
      channels.email.flatMap:
        case Some(sender) => sender.send(email, template.replace("{{code}}", code))
        case None => ZIO.logWarning("SMTP is not configured or is switched off; skipping email delivery")

    override def send(email: Email, message: String): Task[Unit] =
      channels.email.flatMap:
        case Some(sender) => sender.send(email, message)
        case None => ZIO.fail(ChannelUnavailable("email"))
